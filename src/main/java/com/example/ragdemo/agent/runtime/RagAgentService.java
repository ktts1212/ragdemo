package com.example.ragdemo.agent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bsc.langgraph4j.CompiledGraph;
import org.springframework.stereotype.Service;

import com.example.ragdemo.agent.api.AgentRequestException;
import com.example.ragdemo.agent.graph.AgentPrompts;
import com.example.ragdemo.agent.graph.AgentStateCodec;
import com.example.ragdemo.agent.graph.RagAgentGraphFactory;
import com.example.ragdemo.agent.graph.RagAgentState;
import com.example.ragdemo.agent.model.AgentFinishReason;
import com.example.ragdemo.agent.model.AgentMessage;
import com.example.ragdemo.agent.model.AgentSource;
import com.example.ragdemo.agent.model.AgentTraceEntry;
import com.example.ragdemo.agent.tools.AgentToolkitFactory;
import com.example.ragdemo.config.AgentProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 单 Agent 编排服务（Step 3 §2/§7）：一次请求一次 Run，<b>不</b>读写跨请求会话历史。
 * <p>
 * <ul>
 *   <li>每个 Run 新建 Toolkit（绑定受信知识库范围）+ 新建白名单 Registry + 现编译图 ⇒ 无跨 Run 串库/复用；</li>
 *   <li>HTTP 传入的 kbId 必须命中服务端白名单（当前无用户级 RBAC，坦诚以此作为最小授权边界）；</li>
 *   <li>不写 chat_messages、不复用旧会话 threadId（Step 4 再处理记忆与持久化）；</li>
 *   <li>日志只记 runId/计数/终止原因，不记 PDF handle、证据正文与密钥。</li>
 * </ul>
 */
@Slf4j
@Service
public class RagAgentService {

    private static final int MAX_QUESTION_CHARS = 4_000;

    private final AgentProperties properties;
    private final AgentToolkitFactory toolkitFactory;
    private final RagAgentGraphFactory graphFactory;

    /**
     * Step 4：Durable 编排（仅当 {@code rag.agent.durable.enabled=true} 时启用）。
     * <p>用字段注入而非构造器注入，是为了<b>保持既有构造器签名不变</b>（既有单测直接 new 本类），
     * 且默认关闭时该字段为 null、行为与 Step 3 内存态完全一致。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentDurableRunService durableRunService;

    /** Phase 3：L3 长期记忆（字段注入保持既有构造器签名；未启用时为空快照）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.ragdemo.memory.l3.MemoryL3ContextService l3ContextService;

    public RagAgentService(AgentProperties properties, AgentToolkitFactory toolkitFactory,
            RagAgentGraphFactory graphFactory) {
        this.properties = properties;
        this.toolkitFactory = toolkitFactory;
        this.graphFactory = graphFactory;
    }

    /**
     * Step 4D：带 {@code requestKey} 的入口（HTTP 幂等贯通）。
     * <p>
     * {@code durable.enabled=false} 时完全等价于三参重载（旧内存态行为不变）；
     * {@code durable=true} 且 requestKey 为空时仍是"新 Run"语义（与三参一致）。
     */
    public AgentRunResult run(Long knowledgeBaseId, String conversationId, String question, String requestKey) {
        if (properties.isEnabled() && properties.getDurable().isEnabled() && durableRunService != null) {
            return durableRunService.run(knowledgeBaseId, conversationId, question, requestKey).result();
        }
        return run(knowledgeBaseId, conversationId, question);
    }

    /** 默认禁用时：不调用 LLM、不调用任何工具，明确返回 DISABLED。 */
    public AgentRunResult run(Long knowledgeBaseId, String conversationId, String question) {
        // Step 4：Durable 开关打开时改由 Durable 编排执行（幂等/抢占/Checkpoint/对账）；
        // 关闭时完全走下面的 Step 3 内存态路径，行为不变。
        if (properties.isEnabled() && properties.getDurable().isEnabled() && durableRunService != null) {
            return durableRunService.run(knowledgeBaseId, conversationId, question, null).result();
        }
        if (!properties.isEnabled()) {
            return new AgentRunResult("", conversationId, knowledgeBaseId == null ? 0L : knowledgeBaseId, false,
                    "Agent 未启用：请联系维护者设置 rag.agent.enabled=true", List.of(), List.of(),
                    AgentFinishReason.DISABLED.name(), List.of(), List.of(), List.of(), List.of(), 0, 0, 0L);
        }
        if (question == null || question.isBlank()) {
            throw AgentRequestException.invalidArgument("question 不能为空");
        }
        String trimmed = question.trim();
        if (trimmed.length() > MAX_QUESTION_CHARS) {
            throw AgentRequestException.invalidArgument("question 过长（上限 " + MAX_QUESTION_CHARS + " 字符）");
        }
        long kbId = resolveKnowledgeBaseId(knowledgeBaseId);

        long started = System.currentTimeMillis();
        String runId = UUID.randomUUID().toString();

        AgentToolkitFactory.AgentToolkit toolkit = toolkitFactory.createForRun(kbId, null, conversationId);
        ToolRegistry registry = ToolRegistry.forToolkit(toolkit);
        AgentLimits limits = AgentLimits.from(properties);

        CompiledGraph<RagAgentState> graph;
        try {
            graph = graphFactory.compile(registry);
        } catch (Exception e) {
            // GraphStateException 等编译期问题：属于部署/配置错误，快速失败
            throw new IllegalStateException("Agent 图编译失败: " + e.getMessage(), e);
        }

        var initialState = new java.util.LinkedHashMap<String, Object>();
        initialState.put(RagAgentState.AGENT_STATE_SCHEMA_VERSION, AgentStateCodec.SCHEMA_VERSION);
        initialState.put(RagAgentState.RUN_ID, runId);
        initialState.put(RagAgentState.CONVERSATION_ID, conversationId == null ? "" : conversationId);
        initialState.put(RagAgentState.KNOWLEDGE_BASE_ID, kbId);
        initialState.put(RagAgentState.QUESTION, trimmed);
        // Step 4：复杂结构以 JSON 字符串入状态，保证 Checkpoint 可跨 JVM 完整回读
        String l3Context = "";
        java.util.List<AgentMessage> initialMessages = new java.util.ArrayList<>();
        initialMessages.add(AgentMessage.system(AgentPrompts.systemPrompt(kbId, limits)));
        if (l3ContextService != null) {
            try {
                var snapshot = l3ContextService.load(trimmed, false, null);
                if (snapshot.hasContent()) {
                    // L3 以 **user 角色引用数据** 注入，绝不并入 system（Phase 4A 信任边界）
                    l3Context = snapshot.context();
                    initialMessages.add(AgentMessage.user(l3Context));
                }
            } catch (RuntimeException e) {
                log.warn("[agent] L3 检索异常，降级为 L1+L2: {}", e.toString());
            }
        }
        initialMessages.add(AgentMessage.user(trimmed));
        initialState.put(RagAgentState.MESSAGES, AgentStateCodec.encode(initialMessages));
        initialState.put(RagAgentState.L3_CONTEXT, l3Context);
        initialState.put(RagAgentState.L3_SOURCES, "[]");
        initialState.put(RagAgentState.L3_APPLIED, l3Context.isBlank() ? 0 : 1);

        Optional<RagAgentState> finalState;
        try {
            finalState = graph.invoke(initialState);
        } catch (RuntimeException e) {
            log.error("[agent] 图执行失败: runId={}, type={}", runId, e.getClass().getSimpleName());
            return new AgentRunResult(runId, conversationId, kbId, false,
                    "Agent 执行失败（已记录日志），请稍后重试或改用 /api/chat", List.of(), List.of(),
                    AgentFinishReason.ERROR.name(),
                    List.of("AGENT_EXECUTION_FAILED: 内部错误，未向模型暴露细节"), List.of(), List.of(), List.of(),
                    0, 0, System.currentTimeMillis() - started);
        }
        if (finalState.isEmpty()) {
            return new AgentRunResult(runId, conversationId, kbId, false, "Agent 未产生结果", List.of(),
                    List.of(), AgentFinishReason.ERROR.name(), List.of(),
                    List.of("EMPTY_FINAL_STATE"), List.of(), List.of(), 0, 0,
                    System.currentTimeMillis() - started);
        }
        return toResult(finalState.get(), runId, conversationId, kbId, System.currentTimeMillis() - started);
    }

    private long resolveKnowledgeBaseId(Long requested) {
        List<Long> allowed = properties.getAllowedKnowledgeBaseIds();
        if (allowed == null || allowed.isEmpty()) {
            throw new IllegalStateException("未配置 rag.agent.allowed-knowledge-base-ids，拒绝执行");
        }
        if (requested == null) {
            return allowed.get(0);
        }
        if (!allowed.contains(requested)) {
            // 不区分"不存在"与"不允许"，避免探测其他知识库
            throw AgentRequestException.knowledgeBaseNotAllowed();
        }
        return requested;
    }

    private AgentRunResult toResult(RagAgentState state, String runId, String conversationId, long kbId,
            long elapsedMs) {
        List<String> usedTools = new ArrayList<>(new LinkedHashSet<>(
                state.trace().stream().map(AgentTraceEntry::toolName).toList()));
        List<String> handles = new ArrayList<>(new LinkedHashSet<>(state.handles()));
        String finishReason = state.finishReason().isBlank()
                ? AgentFinishReason.ANSWERED.name()
                : state.finishReason();
        boolean incomplete = !AgentFinishReason.ANSWERED.name().equals(finishReason);
        // §3B-3：预算耗尽/循环/工具失败/内部错误时，必须让"不完整"显式可见，
        // 不能让兜底文案看起来像正常完成。
        String answer = state.finalAnswer();
        if (incomplete) {
            answer = "【未完成：" + finishReason + "】本次执行未正常收尾，以下内容可能不完整，请谨慎采信。\n\n"
                    + answer;
        }
        boolean ok = !incomplete && !answer.isBlank();
        log.info("[agent] run 完成: runId={}, kbId={}, turns={}, toolCalls={}, sources={}, finishReason={}, "
                        + "usedTools={}, elapsedMs={}",
                runId, kbId, state.llmTurnsUsed(), state.toolCallsUsed(), state.sources().size(), finishReason,
                usedTools, elapsedMs);
        List<AgentSource> sources = state.sources();
        List<String> warnings = new ArrayList<>(state.warnings());
        if (incomplete) {
            warnings.add("INCOMPLETE_RUN: finishReason=" + finishReason
                    + "（工具预算用尽/循环/工具失败/内部错误），结论可能不完整");
        }
        if (!handles.isEmpty()) {
            warnings.add("HANDLES_RETURNED: 返回 " + handles.size()
                    + " 个受控句柄（PDF/页面图像）；当前没有模型端图像消费能力，不代表视觉核验");
        }
        return new AgentRunResult(runId, conversationId, kbId, ok, answer, sources, usedTools,
                finishReason, warnings, state.errors(), state.trace(), handles,
                state.toolCallsUsed(), state.llmTurnsUsed(), elapsedMs);
    }
}
