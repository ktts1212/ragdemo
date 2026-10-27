package com.example.ragdemo.agent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.ragdemo.agent.model.AgentMessage;
import com.example.ragdemo.agent.model.AgentMessageRole;
import com.example.ragdemo.agent.model.AgentToolCall;

/**
 * Agent 诊断工具（Step 3B）：<b>脱敏日志</b> + <b>协议结构校验</b>。
 * <p>
 * 目标是在不泄漏敏感内容的前提下拿到可定位的证据：
 * <ul>
 *   <li>异常堆栈：打印类名 + <b>脱敏后的 message</b> + 调用帧（超过层数/帧数截断），
 *       避免把供应商原样回显的请求内容、PDF 句柄（{@code v1.*}）、API Key（{@code sk-*}）写进日志；</li>
 *   <li>消息序列结构：只输出"角色 / tool_calls 数 / content 长度 / requestId 指纹"，
 *       不输出正文，用于核对 §2 的清单（一一对应、是否有空 assistant、null 与空串混用等）。</li>
 * </ul>
 */
public final class AgentDiagnostics {

    /** 单条日志字段最大长度（防止把正文/长错误体整段写入）。 */
    private static final int MAX_FIELD_CHARS = 600;
    private static final int MAX_CAUSES = 5;
    private static final int MAX_FRAMES = 12;

    private static final Pattern API_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
    private static final Pattern OPAQUE_HANDLE = Pattern.compile("v1\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer|authorization)[\"'\\s:=]+[A-Za-z0-9._-]+");

    private AgentDiagnostics() {
    }

    // ==================== 脱敏 ====================

    /** 去掉密钥/句柄等敏感片段并截断（用于任何可能来自异常或供应商的内容）。 */
    public static String redact(String raw) {
        if (raw == null) {
            return "";
        }
        String value = API_KEY.matcher(raw).replaceAll("sk-***");
        value = OPAQUE_HANDLE.matcher(value).replaceAll("v1.***.***");
        value = BEARER.matcher(value).replaceAll("$1 ***");
        // 折叠换行，避免日志被正文/错误体刷屏
        value = value.replaceAll("\\s+", " ").trim();
        return value.length() > MAX_FIELD_CHARS ? value.substring(0, MAX_FIELD_CHARS) + "…" : value;
    }

    /**
     * 生成脱敏堆栈摘要：类名 + 脱敏 message + 调用帧。
     * <p>不用 {@code log.error(msg, e)}，因为那会原样打印异常的完整 message（可能含请求回显）。
     */
    public static String stackSummary(Throwable error) {
        if (error == null) {
            return "<no-exception>";
        }
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSES) {
            sb.append(depth == 0 ? "" : "Caused by: ")
                    .append(current.getClass().getName())
                    .append(": ")
                    .append(redact(current.getMessage()))
                    .append('\n');
            StackTraceElement[] frames = current.getStackTrace();
            for (int i = 0; i < frames.length && i < MAX_FRAMES; i++) {
                sb.append("    at ").append(frames[i]).append('\n');
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString().stripTrailing();
    }

    // ==================== 结构描述 ====================

    /** 渲染消息序列结构（无正文）。id 只输出短指纹，既能核对配平又不泄漏 token。 */
    public static String describeStructure(List<AgentMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (AgentMessage message : messages == null ? List.<AgentMessage>of() : messages) {
            sb.append(index++).append(' ').append(describe(message)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public static String describe(AgentMessage message) {
        if (message == null) {
            return "<null-message>";
        }
        String role = message.role() == null ? "NULL_ROLE" : message.role().name();
        String content = contentShape(message);
        if (message.role() == AgentMessageRole.AI && !message.toolCalls().isEmpty()) {
            return role + "(tool_calls=" + message.toolCalls().size() + ", content=" + content
                    + ", ids=" + message.toolCalls().stream().map(call -> idFingerprint(call.id())).toList()
                    + ")";
        }
        if (message.role() == AgentMessageRole.TOOL) {
            return role + "(callId=" + idFingerprint(message.toolCallId()) + ", name=" + message.toolName()
                    + ", content=" + content + ", truncated=" + message.truncated() + ")";
        }
        return role + "(content=" + content + ")";
    }

    /** content 形态：null / empty / len=N —— 用于发现"null 与空串混用"。 */
    private static String contentShape(AgentMessage message) {
        String text = message.text();
        if (text == null) {
            return "null";
        }
        return text.isEmpty() ? "empty" : "len=" + text.length();
    }

    /** requestId 短指纹（前 6 位 + 长度），足够核对配平，不构成敏感信息。 */
    public static String idFingerprint(String id) {
        if (id == null) {
            return "null";
        }
        String head = id.length() <= 6 ? id : id.substring(0, 6);
        return head + "…#" + id.length();
    }

    /**
     * 渲染状态 Map 的<b>形状</b>：键 + 值类型 + 长度/条数，<b>不含任何内容</b>。
     * <p>用于"执行前 / Checkpoint 保存后 / 新 Saver 读回后"三点对比，定位字段丢失或键名不一致。
     */
    public static String describeState(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return "<empty-state>";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : new java.util.TreeSet<>(data.keySet())) {
            sb.append(key).append('=').append(shape(data.get(key))).append(' ');
        }
        return sb.toString().stripTrailing();
    }

    /** 值的形态描述（类型 + 长度/条数），绝不输出内容。 */
    public static String shape(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "String(len=" + text.length() + ")";
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.getClass().getSimpleName() + "(size=" + collection.size() + ")";
        }
        if (value instanceof java.util.Map<?, ?> map) {
            return "Map(size=" + map.size() + ")";
        }
        return value.getClass().getSimpleName();
    }

    // ==================== 协议校验（§2 清单） ====================

    /** 协议违例（kind + 定位信息，无正文）。 */
    public record Violation(String kind, String detail) {
        @Override
        public String toString() {
            return kind + "(" + detail + ")";
        }
    }

    /**
     * 校验消息序列是否满足 OpenAI 兼容协议：
     * <ol>
     *   <li>带 tool_calls 的 assistant 消息：每个 call 必须有 id，且必须被紧随其后的 tool 消息**全部**应答；</li>
     *   <li>tool 消息：必须携带 callId/name，且必须能对应到前面某个未配平的 call（无孤儿）；</li>
     *   <li>不允许空的 assistant 消息（无内容且无 tool_calls）。</li>
     * </ol>
     */
    public static List<Violation> validateProtocol(List<AgentMessage> messages) {
        List<Violation> violations = new ArrayList<>();
        List<AgentMessage> list = messages == null ? List.of() : messages;
        for (int i = 0; i < list.size(); i++) {
            AgentMessage message = list.get(i);
            if (message == null) {
                violations.add(new Violation("NULL_MESSAGE", "index=" + i));
                continue;
            }
            if (message.role() == AgentMessageRole.AI) {
                if (message.toolCalls().isEmpty()) {
                    if (message.text().isEmpty()) {
                        violations.add(new Violation("EMPTY_ASSISTANT", "index=" + i));
                    }
                    continue;
                }
                Set<String> expected = new LinkedHashSet<>();
                for (AgentToolCall call : message.toolCalls()) {
                    if (call.id() == null || call.id().isBlank()) {
                        violations.add(new Violation("MISSING_TOOL_CALL_ID",
                                "index=" + i + ", name=" + call.name()));
                    } else {
                        expected.add(call.id());
                    }
                    if (call.name() == null || call.name().isBlank()) {
                        violations.add(new Violation("MISSING_TOOL_NAME", "index=" + i));
                    }
                }
                Set<String> answered = new LinkedHashSet<>();
                int j = i + 1;
                while (j < list.size() && list.get(j) != null && list.get(j).role() == AgentMessageRole.TOOL) {
                    answered.add(list.get(j).toolCallId());
                    j++;
                }
                if (!answered.containsAll(expected)) {
                    violations.add(new Violation("UNANSWERED_TOOL_CALLS",
                            "index=" + i + ", expected=" + expected.size() + ", answered=" + answered.size()));
                }
            }
            if (message.role() == AgentMessageRole.TOOL
                    && (message.toolCallId() == null || message.toolName() == null)) {
                violations.add(new Violation("ORPHAN_TOOL_MESSAGE", "index=" + i));
            }
        }
        return violations;
    }
}
