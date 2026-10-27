package com.example.ragdemo.agent.api;

/**
 * Agent API 的受控错误（携带 HTTP 状态与稳定 error code）。
 * <p>
 * 用于"未启用""知识库不在白名单""参数非法"等<b>可预期</b>拒绝；内部异常统一由 Controller 兜底成 500 安全文案。
 */
public class AgentRequestException extends RuntimeException {

    private final int status;
    private final String code;

    public AgentRequestException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static AgentRequestException invalidArgument(String message) {
        return new AgentRequestException(400, "INVALID_ARGUMENT", message);
    }

    public static AgentRequestException knowledgeBaseNotAllowed() {
        return new AgentRequestException(403, "AGENT_KB_NOT_ALLOWED",
                "该知识库不在服务端允许的 Agent 访问范围内");
    }

    public static AgentRequestException disabled() {
        return new AgentRequestException(503, "AGENT_DISABLED", "Agent 能力未启用（rag.agent.enabled=false）");
    }
}
