package com.example.ragdemo.agent.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.ragdemo.agent.model.AgentFinishReason;
import com.example.ragdemo.agent.model.AgentMessage;
import com.example.ragdemo.agent.model.AgentSource;
import com.example.ragdemo.agent.model.AgentToolCall;
import com.example.ragdemo.agent.model.AgentTraceEntry;
import com.example.ragdemo.agent.runtime.AgentLimits;
import com.example.ragdemo.agent.runtime.AgentMessageCodec;
import com.example.ragdemo.agent.runtime.EvidenceExtractor;
import com.example.ragdemo.agent.runtime.ToolArgs;
import com.example.ragdemo.agent.runtime.ToolRegistry;
import com.example.ragdemo.agent.tools.model.ToolErrorCode;
import com.example.ragdemo.agent.tools.model.ToolResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 工具执行节点（Step 3 §4/§5）。
 * <p>
 * <ul>
 *   <li><b>顺序执行</b>同轮内的多个 tool calls，避免共享状态竞争；</li>
 *   <li>额度在<b>尝试前</b>扣减（含重复/被拒的调用）⇒ 保证有限步收敛；</li>
 *   <li>相同（工具名+参数）的重复调用被跳过并计数，超出阈值判定为循环（§5 去重/循环检测）；</li>
 *   <li>结果超长时裁剪成合法 JSON 包装并打 {@code truncated} 标记；</li>
 *   <li>trace 只记录名字/requestId/耗时/状态/证据条数，不记录内容与句柄（§4）。</li>
 * </ul>
 */
@Slf4j
public class ToolExecutorNode {

    private static final int EXCERPT_CHARS = 500;

    private final ToolRegistry registry;
    private final AgentLimits limits;
    private final ObjectMapper objectMapper;
    private final com.example.ragdemo.agent.runtime.AgentLeaseGuard guard;

    /** 可恢复的工具错误码（Step 4E-5F）：仅此码允许一次有界恢复机会。 */
    private static final String RECOVERABLE_CODE = "HANDLE_INVALID";
    /** 恢复机会标记：写入 {@code state.errors()} ⇒ 随 Checkpoint 持久化 ⇒ Resume 后不会重置。 */
    public static final String RECOVERY_MARKER = "RECOVERABLE_TOOL_ERROR:";

    public ToolExecutorNode(ToolRegistry registry, AgentLimits limits, ObjectMapper objectMapper) {
        this(registry, limits, objectMapper, null);
    }

    /** Step 4E-4：注入执行权守卫（{@code null} = 无守卫，内存态/非 Durable 路径）。 */
    public ToolExecutorNode(ToolRegistry registry, AgentLimits limits, ObjectMapper objectMapper,
            com.example.ragdemo.agent.runtime.AgentLeaseGuard guard) {
        this.registry = registry;
        this.limits = limits;
        this.objectMapper = objectMapper;
        this.guard = guard;
    }

    /**
     * 执行权校验（故意<b>不</b>被 ToolError 的捕获块包住）：失权必须向上抛出，
     * 不允许被"工具失败"吞掉后继续 Graph 循环。
     */
    private void assertHeld(String where) {
        if (guard != null) {
            guard.assertHeld(where);
        }
    }

    public Map<String, Object> apply(RagAgentState state) {
        // 旧执行者（已被接管/失权）不得启动任何工具调用
        assertHeld("tools:entry");
        Map<String, Object> delta = new LinkedHashMap<>();
        List<AgentToolCall> pending = state.pendingToolCalls();
        if (pending.isEmpty()) {
            return delta;
        }

        Set<String> fingerprints = new HashSet<>(state.callFingerprints());
        List<AgentMessage> messages = new ArrayList<>(state.messages());
        List<AgentSource> sources = new ArrayList<>(state.sources());
        List<String> handles = new ArrayList<>(state.handles());
        List<AgentTraceEntry> trace = new ArrayList<>(state.trace());
        List<String> errors = new ArrayList<>(state.errors());
        List<String> warnings = new ArrayList<>(state.warnings());

        int used = state.toolCallsUsed();
        int duplicates = state.duplicateCalls();
        int contextChars = state.contextChars();
        int step = trace.size();
        int failures = 0;
        List<String> failedCodes = new ArrayList<>();
        String finishReason = state.finishReason();

        for (int index = 0; index < pending.size(); index++) {
            AgentToolCall call = pending.get(index);
            // Step 4E-4B：**每个 Tool Call 之前**都校验执行权 ——
            // 旧 Worker 在 Tool A 执行后失权，则 B/C 连"开始"都不允许（抛 409，不伪造 Tool Result）。
            // 此处刻意不在任何 catch 内：AGENT_LEASE_LOST 必须向上传播，不得被 ToolError 吞掉。
            assertHeld("tools:before-call[" + index + "]:" + call.name());
            if (used >= limits.maxToolCalls()) {
                finishReason = AgentFinishReason.BUDGET_EXHAUSTED.name();
                errors.add("BUDGET_EXHAUSTED: 工具调用总额度 " + limits.maxToolCalls() + " 已用尽");
                // 协议约束：每个 tool_call 都必须有对应的 tool 消息，未执行的要补"未执行"结果
                contextChars += appendNotExecuted(messages, trace, pending.subList(index, pending.size()),
                        "BUDGET_EXHAUSTED", step);
                break;
            }
            used++;
            step++;
            long started = System.currentTimeMillis();
            String outcome;
            String resultJson;
            boolean truncated = false;
            int evidenceCount = 0;

            if (fingerprints.contains(call.fingerprint())) {
                duplicates++;
                outcome = "DUPLICATE_SKIPPED";
                resultJson = toJson(ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT,
                        "重复调用（相同工具与参数）已被跳过，请改用其它参数或直接回答"));
                warnings.add("DUPLICATE_TOOL_CALL: " + call.name() + " 相同参数重复调用已跳过");
                if (duplicates > limits.maxDuplicateCalls()) {
                    finishReason = AgentFinishReason.LOOP_DETECTED.name();
                    errors.add("LOOP_DETECTED: 重复调用超过上限 " + limits.maxDuplicateCalls());
                }
            } else {
                fingerprints.add(call.fingerprint());
                ToolResult<?> result = registry.invoke(call.name(), call.arguments());
                // Step 4E-4B：长耗时 Tool 返回后、**在处理结果之前**再校验一次执行权；
                // 失权即抛（早于 tool 消息/来源/trace 写入 ⇒ 不会产生伪造 Tool Result）。
                assertHeld("tools:after-call[" + index + "]:" + call.name());
                if (!result.ok()) {
                    failures++;
                    String code = result.error() == null ? "UNKNOWN" : result.error().code().name();
                    failedCodes.add(code);
                    outcome = "ERROR:" + code;
                } else {
                    outcome = "OK";
                }
                EvidenceExtractor.Extraction extraction = EvidenceExtractor.extract(result, EXCERPT_CHARS,
                        documentIdFromArguments(call));
                sources.addAll(extraction.sources());
                handles.addAll(extraction.handles());
                evidenceCount = extraction.sources().size();
                Capped capped = capJson(toJson(result), limits.maxToolResultChars());
                resultJson = capped.json();
                truncated = capped.truncated();
                if (truncated) {
                    warnings.add("TOOL_RESULT_TRUNCATED: " + call.name() + " 结果超过 "
                            + limits.maxToolResultChars() + " 字符已裁剪");
                }
            }

            AgentMessage toolMessage = AgentMessageCodec.toolResult(call, resultJson, truncated);
            messages.add(toolMessage);
            contextChars += toolMessage.charCount();
            trace.add(new AgentTraceEntry(step, call.name(), call.id(), outcome,
                    System.currentTimeMillis() - started, evidenceCount, truncated));

            if (AgentFinishReason.LOOP_DETECTED.name().equals(finishReason)) {
                // 同上：剩余未执行的调用也必须补齐 tool 消息
                contextChars += appendNotExecuted(messages, trace, pending.subList(index + 1, pending.size()),
                        "LOOP_DETECTED", step);
                break;
            }
        }

        // 全部工具都失败且本轮没有任何证据 ⇒ 默认标记 TOOL_FAILURE（finalize 仍会如实作答）。
        // Step 4E-5F：仅对**可恢复**错误（HANDLE_INVALID）保留一次有界恢复机会 —— 让 LLM 有机会用
        // **可信 documentId** 重新调用 getOriginalPdf；机会用尽（errors 已有标记）、预算不足、
        // 或错误不可恢复（如 NOT_FOUND_OR_FORBIDDEN）时一律照常收敛为 TOOL_FAILURE。
        if (failures > 0 && failures == pending.size() && sources.isEmpty() && finishReason.isBlank()) {
            boolean recoverable = failedCodes.contains(RECOVERABLE_CODE)
                    && !recoveryAlreadyUsed(state)
                    && used < limits.maxToolCalls()
                    && state.llmTurnsUsed() < limits.maxLlmTurns();
            if (recoverable) {
                errors.add(RECOVERY_MARKER + RECOVERABLE_CODE
                        + "：pdfHandle 已失效（跨进程/过期），可用**可信 documentId** 重新调用 getOriginalPdf "
                        + "获取新句柄后继续；本 Run 仅一次恢复机会");
                // 允许重新签发句柄：移除 getOriginalPdf 指纹，避免"重复调用保护"挡掉重授权
                fingerprints.removeIf(fingerprint -> fingerprint.startsWith("getOriginalPdf|"));
            } else {
                finishReason = AgentFinishReason.TOOL_FAILURE.name();
            }
        }

        // Step 4：复杂结构一律以 JSON 字符串写回状态（Checkpoint 可跨 JVM 回读）
        delta.put(RagAgentState.MESSAGES, AgentStateCodec.encode(messages));
        delta.put(RagAgentState.SOURCES, AgentStateCodec.encode(sources));
        delta.put(RagAgentState.HANDLES, AgentStateCodec.encode(handles));
        delta.put(RagAgentState.TRACE, AgentStateCodec.encode(trace));
        delta.put(RagAgentState.ERRORS, AgentStateCodec.encode(errors));
        delta.put(RagAgentState.WARNINGS, AgentStateCodec.encode(warnings));
        delta.put(RagAgentState.CALL_FINGERPRINTS, AgentStateCodec.encode(new ArrayList<>(fingerprints)));
        delta.put(RagAgentState.PENDING_TOOL_CALLS, AgentStateCodec.encode(List.of()));
        delta.put(RagAgentState.TOOL_CALLS_USED, used);
        delta.put(RagAgentState.DUPLICATE_CALLS, duplicates);
        delta.put(RagAgentState.CONTEXT_CHARS, contextChars);
        delta.put(RagAgentState.LAST_AI_TEXT, "");
        if (!finishReason.isBlank()) {
            delta.put(RagAgentState.FINISH_REASON, finishReason);
        }
        return delta;
    }

    /** 恢复机会是否已用尽（标记随 Checkpoint 持久化 ⇒ Resume 后仍然有效）。 */
    private static boolean recoveryAlreadyUsed(RagAgentState state) {
        return state.errors().stream().anyMatch(error -> error.startsWith(RECOVERY_MARKER));
    }

    private record Capped(String json, boolean truncated) {
    }

    /**
     * 为"未执行"的 tool call 补一条工具结果消息。
     * <p>
     * <b>必需</b>：OpenAI 兼容协议要求每个 {@code tool_calls} 条目都必须有对应 {@code tool} 消息，
     * 否则下一次请求会被供应商拒绝（实测 DeepSeek 返回 {@code invalid_request_error}）。
     * 同时这对模型也是诚实的信号：该调用没有执行。
     *
     * @return 新增的上下文字符数
     */
    private int appendNotExecuted(List<AgentMessage> messages, List<AgentTraceEntry> trace,
            List<AgentToolCall> notExecuted, String reason, int startStep) {
        int addedChars = 0;
        int step = startStep;
        for (AgentToolCall call : notExecuted) {
            step++;
            String json = toJson(ToolResult.fail(ToolErrorCode.INVALID_ARGUMENT,
                    "NOT_EXECUTED: 该工具调用未执行（" + reason + "）"));
            AgentMessage message = AgentMessageCodec.toolResult(call, json, false);
            messages.add(message);
            addedChars += message.charCount();
            trace.add(new AgentTraceEntry(step, call.name(), call.id(), "SKIPPED:" + reason, 0L, 0, false));
        }
        return addedChars;
    }

    /** 从调用参数里取 documentId（用于补齐那些返回值不含文档 id 的来源，保证可审计）。 */
    private Long documentIdFromArguments(AgentToolCall call) {
        try {
            return ToolArgs.parse(call.arguments()).optionalLong("documentId");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 超长结果裁剪为<b>合法</b> JSON 包装（不破坏模型解析），并保留一段原文预览。 */
    private Capped capJson(String json, int maxChars) {
        if (json == null) {
            return new Capped("{}", false);
        }
        if (json.length() <= maxChars) {
            return new Capped(json, false);
        }
        try {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("truncated", true);
            wrapper.put("note", "TOOL_RESULT_TRUNCATED: 结果超过服务端上限 " + maxChars + " 字符，仅保留预览");
            wrapper.put("preview", json.substring(0, Math.max(0, maxChars)));
            return new Capped(objectMapper.writeValueAsString(wrapper), true);
        } catch (Exception e) {
            return new Capped("{\"truncated\":true,\"note\":\"TOOL_RESULT_TRUNCATED\"}", true);
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("[agent] 工具结果序列化失败: type={}", payload == null ? "null"
                    : payload.getClass().getSimpleName());
            return "{\"ok\":false,\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"结果序列化失败\"}}";
        }
    }
}
