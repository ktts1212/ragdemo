package com.example.ragdemo.agent.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.stereotype.Component;

import com.example.ragdemo.agent.llm.AgentChatClient;
import com.example.ragdemo.agent.model.AgentMessage;
import com.example.ragdemo.agent.model.AgentSource;
import com.example.ragdemo.agent.model.AgentToolCall;
import com.example.ragdemo.agent.model.AgentTraceEntry;
import com.example.ragdemo.agent.runtime.AgentLimits;
import com.example.ragdemo.agent.runtime.ToolRegistry;
import com.example.ragdemo.config.AgentProperties;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 单 Agent 图（Step 3 §2）：
 * <pre>
 * START → decide ─┬─(有待执行工具)→ tools → decide …（循环）
 *                 └─(无待执行工具 / 预算用尽)→ finalize → END
 * </pre>
 * <b>每次 Run 现编译一个图</b>：节点持有该 Run 的 {@link ToolRegistry}（其工具绑定受信知识库范围）
 * ⇒ 不存在跨 Run 复用工具对象或串库的可能（§8.1.6）；也不需要把 Toolkit 放进状态或全局变量。
 * <p>
 * {@code recursionLimit} 按"轮次 × 2 + 余量"设置；但真正的收敛保障是节点内的预算/循环检测，
 * 不依赖 recursionLimit 兜底（§5）。
 * <p>
 * 本步<b>不配置 CheckpointSaver</b> ⇒ 仅单请求内存态，不宣称跨进程恢复（Step 4 再做）。
 */
@Component
public class RagAgentGraphFactory {

    public static final String NODE_DECISION = "decide";
    public static final String NODE_TOOLS = "tools";
    public static final String NODE_FINALIZE = "finalize";

    private final AgentChatClient chatClient;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    public RagAgentGraphFactory(AgentChatClient chatClient, AgentProperties properties,
            ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 状态 schema：全部 last-write（节点返回绝对值）；复杂结构以 JSON 字符串存放（Step 4 可持久化）。 */
    public static Map<String, Channel<?>> schema() {
        Map<String, Channel<?>> schema = new HashMap<>();
        schema.put(RagAgentState.AGENT_STATE_SCHEMA_VERSION, Channels.<Integer>base(() -> 1));
        schema.put(RagAgentState.RUN_ID, Channels.<String>base(() -> ""));
        schema.put(RagAgentState.CONVERSATION_ID, Channels.<String>base(() -> ""));
        schema.put(RagAgentState.KNOWLEDGE_BASE_ID, Channels.<Long>base(() -> 0L));
        schema.put(RagAgentState.QUESTION, Channels.<String>base(() -> ""));
        schema.put(RagAgentState.MESSAGES, Channels.<String>base(() -> "[]"));
        schema.put(RagAgentState.SOURCES, Channels.<String>base(() -> "[]"));
        schema.put(RagAgentState.HANDLES, Channels.<String>base(() -> "[]"));
        schema.put(RagAgentState.TRACE, Channels.<String>base(() -> "[]"));
        schema.put(RagAgentState.ERRORS, Channels.<String>base(() -> "[]"));
        schema.put(RagAgentState.WARNINGS, Channels.<String>base(() -> "[]"));
        schema.put(RagAgentState.CALL_FINGERPRINTS, Channels.<String>base(() -> "[]"));
        schema.put(RagAgentState.PENDING_TOOL_CALLS, Channels.<String>base(() -> "[]"));
        schema.put(RagAgentState.TOOL_CALLS_USED, Channels.<Integer>base(() -> 0));
        schema.put(RagAgentState.LLM_TURNS_USED, Channels.<Integer>base(() -> 0));
        schema.put(RagAgentState.DUPLICATE_CALLS, Channels.<Integer>base(() -> 0));
        schema.put(RagAgentState.CONTEXT_CHARS, Channels.<Integer>base(() -> 0));
        schema.put(RagAgentState.LAST_AI_TEXT, Channels.<String>base(() -> ""));
        schema.put(RagAgentState.FINISH_REASON, Channels.<String>base(() -> ""));
        schema.put(RagAgentState.FINAL_ANSWER, Channels.<String>base(() -> ""));
        // Phase 3：L3 冻结快照（缺失时为空字符串，旧 Checkpoint 不受影响）
        schema.put(RagAgentState.L3_CONTEXT, Channels.<String>base(() -> ""));
        schema.put(RagAgentState.L3_SOURCES, Channels.<String>base(() -> "[]"));
        schema.put(RagAgentState.L3_APPLIED, Channels.<Integer>base(() -> 0));
        return schema;
    }

    public CompiledGraph<RagAgentState> compile(ToolRegistry registry) throws GraphStateException {
        return compile(registry, null);
    }

    /**
     * 带 Checkpoint 的编译（Step 4 §5）。
     * <p>
     * {@code releaseThread(false)} <b>必须</b>：否则图到 END 后 LangGraph4j 会删除该 thread 的
     * checkpoint 行，崩溃恢复/回读就无从谈起（与 IngestionGraph 的结论一致）。
     * 不设置 {@code dropTablesFirst}（禁止）；threadId 空间由调用方加 {@code agent-} 前缀隔离。
     */
    public CompiledGraph<RagAgentState> compile(ToolRegistry registry, BaseCheckpointSaver checkpointSaver)
            throws GraphStateException {
        return compile(registry, checkpointSaver, (java.util.Collection<String>) null);
    }

    /**
     * 带<b>执行权守卫</b>的编译（Step 4E-4）：{@code guard} 为 {@code null} 时退化为无守卫
     * （内存态 / 非 Durable 路径行为不变）。守卫是<b>每次 Run 独立</b>的实例，由工厂在编译时注入节点，
     * 不进状态、不进 Checkpoint。
     */
    public CompiledGraph<RagAgentState> compile(ToolRegistry registry, BaseCheckpointSaver checkpointSaver,
            com.example.ragdemo.agent.runtime.AgentLeaseGuard guard) throws GraphStateException {
        return compileInternal(registry, checkpointSaver, null, guard);
    }

    /**
     * 带 Checkpoint 与<b>测试专用中断注入</b>的编译。
     * <p>
     * {@code interruptsBefore} 非空时在该节点<b>执行前</b>中断（产生 nextNodeId 指向该节点的真实中间
     * Checkpoint）——仅用于验证"服务层 Resume 从中间节点继续"，生产调用只使用两参重载（不注入中断）。
     */
    public CompiledGraph<RagAgentState> compile(ToolRegistry registry, BaseCheckpointSaver checkpointSaver,
            java.util.Collection<String> interruptsBefore) throws GraphStateException {
        return compileInternal(registry, checkpointSaver, interruptsBefore, null);
    }

    private CompiledGraph<RagAgentState> compileInternal(ToolRegistry registry, BaseCheckpointSaver checkpointSaver,
            java.util.Collection<String> interruptsBefore,
            com.example.ragdemo.agent.runtime.AgentLeaseGuard guard) throws GraphStateException {
        AgentLimits limits = AgentLimits.from(properties);
        AgentDecisionNode decisionNode = new AgentDecisionNode(chatClient, registry, limits, guard);
        ToolExecutorNode toolsNode = new ToolExecutorNode(registry, limits, objectMapper, guard);
        FinalizeAnswerNode finalizeNode = new FinalizeAnswerNode(chatClient, limits, guard);

        StateGraph<RagAgentState> graph = new StateGraph<>(schema(), new AgentStateSerializer());
        graph.addNode(NODE_DECISION, AsyncNodeAction.node_async(decisionNode::apply));
        graph.addNode(NODE_TOOLS, AsyncNodeAction.node_async(toolsNode::apply));
        graph.addNode(NODE_FINALIZE, AsyncNodeAction.node_async(finalizeNode::apply));

        graph.addEdge(StateGraph.START, NODE_DECISION);
        graph.addConditionalEdges(NODE_DECISION, AsyncEdgeAction.edge_async(this::routeAfterDecision),
                Map.of(NODE_TOOLS, NODE_TOOLS, NODE_FINALIZE, NODE_FINALIZE));
        graph.addEdge(NODE_TOOLS, NODE_DECISION);
        graph.addEdge(NODE_FINALIZE, StateGraph.END);

        // LG4J 的 iteration 计数包含条件边的超步；真正的收敛保障是节点内的预算/循环检测，
        // 这里给足余量以免正常路径被 recursionLimit 提前打断（不是靠它防重复）。
        int recursionLimit = Math.max(16, limits.maxLlmTurns() * 4 + 8);
        CompileConfig.Builder config = CompileConfig.builder().recursionLimit(recursionLimit);
        if (checkpointSaver != null) {
            config.checkpointSaver(checkpointSaver).releaseThread(false);
        }
        if (interruptsBefore != null && !interruptsBefore.isEmpty()) {
            config.interruptsBefore(interruptsBefore);
        }
        return graph.compile(config.build());
    }

    /** 条件边：还有待执行工具就继续执行，否则收敛。 */
    private String routeAfterDecision(RagAgentState state) {
        return state.pendingToolCalls().isEmpty() ? NODE_FINALIZE : NODE_TOOLS;
    }
}
