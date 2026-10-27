package com.example.ragdemo.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 单 Agent（Day 2 Step 3）配置：{@code rag.agent.*}。
 * <p>
 * 预算与上限全部由服务端持有，模型无法通过工具参数提高；{@code enabled} 默认 {@code false}
 * ⇒ Agent API 与相关 Bean 不生效（或返回 {@code AGENT_DISABLED}），旧 {@code /api/chat} 零影响。
 * <p>
 * {@code allowedKnowledgeBaseIds}：当前没有用户级 RBAC，因此 HTTP 传入的 kbId <b>必须</b>落在
 * 服务端预配置白名单内才被接受；生产环境应只配置单一受控知识库（Step 3 §7）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.agent")
public class AgentProperties {

    /** 是否启用 Agent API（默认关闭）。 */
    private boolean enabled = false;

    /** 整个 Run 的工具调用总预算（非每轮）。 */
    private int maxToolCalls = 8;

    /** 整个 Run 的 LLM 决策轮次上限。 */
    private int maxLlmTurns = 6;

    /** 送入 LLM 的上下文总字符上限（含工具结果）。 */
    private int maxContextChars = 45_000;

    /** 单条工具结果回填给 LLM 的字符上限。 */
    private int maxToolResultChars = 16_000;

    /** 最终答案输出字符上限。 */
    private int maxOutputChars = 12_000;

    /** 同一（工具名+参数）重复调用的允许次数，超出即判定循环。 */
    private int maxDuplicateCalls = 1;

    /** 单次 Run 的执行超时（毫秒），用于 API 层保护。 */
    private long runTimeoutMs = 180_000L;

    /** 允许访问的知识库白名单（无 RBAC 时的最小授权边界）。 */
    private List<Long> allowedKnowledgeBaseIds = List.of(1L);

    /** Agent 使用的模型 key（缺省用 rag.default-model）。 */
    private String modelKey;

    /** Day 2 Step 4：记忆 / Checkpoint / 恢复 相关配置（默认全部关闭 ⇒ 退回 Step 3 内存态）。 */
    private Durable durable = new Durable();

    @Data
    public static class Durable {

        /** 总闸：是否启用 PostgreSQL Checkpoint 与跨 JVM 恢复（默认关闭）。 */
        private boolean enabled = false;

        /** 是否启用会话记忆复用（加载历史窗口、终态提交本轮问答）。 */
        private boolean memoryEnabled = false;

        /** 会话记忆窗口（最近 N 条消息，与 /api/chat 的口径一致）。 */
        private int memoryWindowMessages = 16;

        /** 注入 Agent 的历史消息条数上限（有界，避免跨轮历史无限增长）。 */
        private int historyMaxMessages = 8;

        /** 单个历史消息注入的最大字符数。 */
        private int historyMaxCharsPerMessage = 1_000;

        /** Run 恢复时允许的最大 checkpoint 介入次数（防恢复风暴）。 */
        private int maxResumeAttempts = 3;

        /** 终态后保留 Run Registry 行的天数（清理脚本参考值，本步不自动清理）。 */
        private int runRetentionDays = 30;

        /**
         * Step 4E：Agent 执行租约 TTL（秒）。
         * <p>必须大于单次 LLM + 工具调用的最长耗时；当前版本<b>没有</b>执行期心跳（见交接单 §5.4），
         * 因此在图执行结束后会先续租再提交终态；续租失败即拒绝提交（防止过期执行者写终态）。
         */
        private long leaseTtlSeconds = 600L;

        /**
         * Step 4E-4：执行期<b>心跳间隔</b>（秒）。必须明显小于 {@link #leaseTtlSeconds}；
         * 守卫会防御性收敛到 TTL/4。{@code <= 0} 表示不启动心跳。
         */
        private long leaseHeartbeatSeconds = 30L;
    }
}
