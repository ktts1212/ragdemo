package com.example.ragdemo.memory.l3;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * L3 长期记忆配置（Memory V1 · Phase 2）。
 * <p><b>默认全部关闭</b>：{@code enabled=false} 时系统行为与 Phase 1 完全一致（不建任务、不写记忆）。
 * <p><b>身份边界</b>：{@code singleUserPrincipal} 只能来自服务端配置；客户端请求体、Tool 输出、
 * 模型输出都**不得**影响它。未配置时视为"无可信身份"⇒ 拒绝写入，绝不以 null/空值兜底成全局 owner。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.memory")
public class MemoryProperties {

    private L3 l3 = new L3();

    @Data
    public static class L3 {
        /** 总开关：默认关闭（受限单用户试验环境才允许开启）。 */
        private boolean enabled = false;

        /** 服务端固定身份（V1 单用户模式）。为空 ⇒ 判定为无可信身份，拒绝写入。 */
        private String singleUserPrincipal = "";

        /** 单条记忆内容上限（字符），超出直接拒绝而不是截断。 */
        private int maxContentChars = 500;

        /** 同一会话两次提取之间的最小间隔（秒），用于避免每轮都调用提取模型。 */
        private long minIntervalSeconds = 0;

        /** 单次提取允许返回的候选上限。 */
        private int maxCandidatesPerJob = 8;

        /** 任务最大尝试次数（超过置 DEAD）。 */
        private int maxAttempts = 3;

        /** 任务超时（秒）：RUNNING 超过此时间视为过期，可被重新领取。 */
        private long jobTimeoutSeconds = 600;

        /**
         * Worker 轮询开关：**默认关闭**（与 {@code enabled} 一致，双开关避免测试/无意启动后台线程）。
         * 生产启用 L3 时需同时设置 {@code rag.memory.l3.worker-enabled=true}。
         */
        private boolean workerEnabled = false;

        /** 单批领取上限。 */
        private int workerBatchSize = 5;

        /** 空闲轮询间隔（毫秒）。 */
        private long workerIdleMillis = 2000L;

        // ==================== Phase 3：读取链路 ====================

        /** 单次检索返回上限（默认 5）。 */
        private int retrievalTopK = 5;

        /** 语义检索最小 cosine 相似度（0~1，默认 0.55）。 */
        private double retrievalMinSimilarity = 0.55d;

        /** L3 注入的 Token 预算（近似估算，默认 600）。 */
        private int contextTokenBudget = 600;

        /** 注入条数上限（默认 5）。 */
        private int maxInjectedItems = 5;

        /** 规则路由开关（关闭后仅在显式请求时检索）。 */
        private boolean routerEnabled = true;

        /**
         * 跨槽（canonical_key 不同）UPDATE 的最低**正文向量相似度**（Phase 4A 收紧）。
         * <p>仅"同类型唯一 ACTIVE"不足以证明是同一记忆槽位，必须同时满足：谓词一致 + 主语重叠 + 本阈值。
         */
        private double crossSlotMinContentSimilarity = 0.70d;

        /** 跨槽 UPDATE 的最低**主语字符二元组 Jaccard 重叠**（"项目向量模型" vs "向量模型" ≈ 0.6）。 */
        private double crossSlotMinSubjectOverlap = 0.5d;

        /**
         * 跨槽 UPDATE 的**高置信正文相似度**（Phase 4A 实测：同语义更新 ≈0.85，不同主题 ≈0.59~0.65）。
         * <p>满足它可替代主语重叠条件（应对模型换措辞）；但不放宽谓词一致与"唯一匹配"要求。
         */
        private double crossSlotHighConfidenceSimilarity = 0.80d;
    }
}
