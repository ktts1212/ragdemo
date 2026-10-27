package com.example.ragdemo.agent.model;

import java.util.UUID;

/**
 * 模型发起的单次工具调用（规范化自 LangChain4j 的 {@code ToolExecutionRequest}）。
 * <p>
 * 保留 {@code id}（requestId）以便把工具结果按协议回填给模型；{@code arguments} 保持原始 JSON 文本。
 * <p>
 * <b>边界归一化（Step 3B）</b>：真实供应商偶发返回缺失 {@code id} 或 {@code arguments=null} 的
 * tool call，而 LangChain4j 的 {@code ToolExecutionRequest.Builder.build()} 对 null 参数不健壮
 * （实测触发 NPE）。这里统一兜底：
 * <ul>
 *   <li>{@code id} 缺失 ⇒ 生成一次性的合成 id（同一对象同时用于 AI 消息与 tool 结果 ⇒ 配对语义仍然一致）；</li>
 *   <li>{@code arguments} 缺失/空白 ⇒ 归一为 {@code "{}"}（由工具层的参数校验给出可操作的错误）。</li>
 * </ul>
 */
public record AgentToolCall(String id, String name, String arguments) {

    public AgentToolCall {
        id = (id == null || id.isBlank()) ? "call_auto_" + UUID.randomUUID().toString().substring(0, 8) : id;
        arguments = (arguments == null || arguments.isBlank()) ? "{}" : arguments;
        name = name == null ? "" : name;
    }

    public String fingerprint() {
        return name + "|" + arguments.trim();
    }
}
