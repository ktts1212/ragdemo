package com.example.ragdemo.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次 Tool 调用的受信执行上下文（Step 2 §3，P0）。
 * <p>
 * <b>为什么必须是对象而不是参数</b>：{@code knowledgeBaseId} 由受信应用层注入，
 * 绝不出现在 LLM 可见的工具 schema 里 —— 这样模型无法"自己指定或覆盖知识库范围"。
 * <p>
 * <b>为什么不用 ThreadLocal/Singleton</b>：那会导致并发请求之间串库。本对象由
 * {@link AgentToolkitFactory} 在每次 Run 创建工具实例时<b>按实例</b>绑定，并作为显式参数
 * 传给 Service 层（Service 不持有当前知识库状态）。
 * <p>
 * 无可靠身份体系时 {@code principalId} 为 null：本对象只表达"使用哪个已受信知识库范围"，
 * <b>不伪称</b>实现了用户级 RBAC。
 */
public final class PaperToolExecutionContext {

    private final long knowledgeBaseId;
    private final String principalId;
    private final String conversationId;
    private final PaperToolLimits limits;

    private PaperToolExecutionContext(long knowledgeBaseId, String principalId, String conversationId,
            PaperToolLimits limits) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.principalId = principalId;
        this.conversationId = conversationId;
        this.limits = limits;
    }

    public static PaperToolExecutionContext of(long knowledgeBaseId, PaperToolLimits limits) {
        return builder().knowledgeBaseId(knowledgeBaseId).limits(limits).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public long knowledgeBaseId() {
        return knowledgeBaseId;
    }

    /** 可为 null：当前仓库没有真实身份体系，不伪造。 */
    public String principalId() {
        return principalId;
    }

    /** 可为 null：仅用于日志/审计关联，不参与授权判定。 */
    public String conversationId() {
        return conversationId;
    }

    public PaperToolLimits limits() {
        return limits;
    }

    /** 日志用范围描述（只含 kbId，不含任何内容/密钥）。 */
    public String describeScope() {
        return "kb=" + knowledgeBaseId + (principalId == null ? "" : ", principal=" + principalId);
    }

    /** 上下文完整快照（用于结构化日志，禁止放入用户内容）。 */
    public Map<String, Object> auditFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("knowledgeBaseId", knowledgeBaseId);
        fields.put("principalId", principalId);
        fields.put("conversationId", conversationId);
        return fields;
    }

    public static final class Builder {
        private Long knowledgeBaseId;
        private String principalId;
        private String conversationId;
        private PaperToolLimits limits;

        public Builder knowledgeBaseId(Long knowledgeBaseId) {
            this.knowledgeBaseId = knowledgeBaseId;
            return this;
        }

        public Builder principalId(String principalId) {
            this.principalId = principalId;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder limits(PaperToolLimits limits) {
            this.limits = limits;
            return this;
        }

        public PaperToolExecutionContext build() {
            Objects.requireNonNull(knowledgeBaseId, "受信上下文缺少 knowledgeBaseId：Tool 不得在无知识库范围下执行");
            if (knowledgeBaseId <= 0) {
                throw new IllegalArgumentException("knowledgeBaseId 必须为正数: " + knowledgeBaseId);
            }
            return new PaperToolExecutionContext(knowledgeBaseId, principalId, conversationId,
                    limits == null ? PaperToolLimits.defaults() : limits);
        }
    }
}
