package com.example.ragdemo.memory.l3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ragdemo.mapper.MemoryExtractionJobMapper;
import com.example.ragdemo.model.entity.MemoryExtractionJob;

import lombok.extern.slf4j.Slf4j;

/**
 * L3 记忆提取 Outbox（Memory V1 · Phase 2A）。
 * <p><b>只做可靠入队/领取/成败标记/过期恢复</b>：本类**不调用任何 LLM**（Extractor 属下一阶段），
 * 也**不触碰**已提交的会话消息 —— 入队与消息写入通过调用方事务绑定（见 {@code AgentMemoryCommitter}
 * 与 {@code L3TurnCommitService}）。
 * <p>并发语义：
 * <ul>
 *   <li>同一 {@code idempotency_key} 只有一条任务（DB 唯一约束 + {@code ON CONFLICT DO NOTHING}）；</li>
 *   <li>领取在事务内用 {@code FOR UPDATE SKIP LOCKED}，多 Worker 不会重复领取同一任务；</li>
 *   <li>成败提交必须携带 {@code lease_owner + generation}，过期 Worker 的迟到提交返回 0 行。</li>
 * </ul>
 */
@Slf4j
@Service
public class MemoryExtractionJobService {

    /** 提取策略版本：进入幂等键；策略升级后可重新提取（Phase 2B 会随 Extractor 一起提升）。 */
    public static final String EXTRACTOR_VERSION = "extractor-v0";

    private final MemoryExtractionJobMapper mapper;
    private final MemoryProperties properties;
    private final L3PrincipalResolver principalResolver;

    public MemoryExtractionJobService(MemoryExtractionJobMapper mapper, MemoryProperties properties,
            L3PrincipalResolver principalResolver) {
        this.mapper = mapper;
        this.properties = properties;
        this.principalResolver = principalResolver;
    }

    /**
     * 幂等入队（应由**调用方事务**包裹，以便与消息写入原子提交）。
     *
     * @return true = 本次创建；false = 已存在 / L3 关闭 / 无可信身份 / 缺少消息标识（均**不**建任务）
     */
    public boolean enqueueIfAbsent(String conversationId, String sourceMessageId, String sourceRunId) {
        if (!principalResolver.available()) {
            return false; // L3 默认关闭：不创建任何任务（现有行为不变）
        }
        if (conversationId == null || conversationId.isBlank()
                || sourceMessageId == null || sourceMessageId.isBlank()) {
            log.debug("[l3-outbox] 跳过入队（缺少会话或消息标识）: conversationId={}", conversationId);
            return false;
        }
        String principal = principalResolver.principal();
        String key = idempotencyKey(conversationId, sourceMessageId);
        int inserted = mapper.insertIfAbsent(principal, conversationId, sourceMessageId, sourceRunId, key,
                EXTRACTOR_VERSION);
        if (inserted == 1) {
            log.info("[l3-outbox] 已入队: conversationId={}, messageId={}, key={}", conversationId, sourceMessageId,
                    key.substring(0, 12));
        }
        return inserted == 1;
    }

    /** 以"会话 + 最新 user 消息 id + 策略版本"构造幂等键（**不允许只用 conversationId**）。 */
    public String latestMessageIdempotencyKey(String conversationId) {
        String messageId = mapper.selectLatestUserMessageId(conversationId);
        return messageId == null ? null : idempotencyKey(conversationId, messageId);
    }

    /**
     * 便捷入口：用"该会话最新 user 消息"入队（应在调用方事务内调用，L3 关闭时返回 false）。
     * <p>消息必须有 id（已提交）；否则不建任务，避免同会话多轮被错误去重。
     */
    public boolean enqueueForLatestTurn(String conversationId, String sourceRunId) {
        if (!principalResolver.available()) {
            return false;
        }
        String messageId = mapper.selectLatestUserMessageId(conversationId);
        if (messageId == null) {
            log.debug("[l3-outbox] 跳过入队（查不到最新 user 消息）: conversationId={}", conversationId);
            return false;
        }
        return enqueueIfAbsent(conversationId, messageId, sourceRunId);
    }

    static String idempotencyKey(String conversationId, String sourceMessageId) {
        return sha256(conversationId + "|" + sourceMessageId + "|" + EXTRACTOR_VERSION);
    }

    /**
     * 领取一批任务（事务内 {@code FOR UPDATE SKIP LOCKED}）。返回领取到的任务（未领取到返回空列表）。
     */
    @Transactional
    public List<MemoryExtractionJob> claimPending(String owner, int limit) {
        if (!principalResolver.available()) {
            return List.of(); // L3 关闭：worker 不领取任何任务
        }
        String principal = principalResolver.principal();
        int capped = Math.max(1, Math.min(limit, 50));
        List<Long> candidates = mapper.selectClaimable(principal, capped);
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 用 @Update 执行领取（实测 @Select 执行 UPDATE…RETURNING 在本工程不落库），再按
        // "owner + RUNNING + updated_at >= claim 开始时刻" 精确读回本次领取的行。
        // Phase 4B：窗口从 1s 放宽到 10s —— 多上下文/高负载下 Java 与 PG 的时钟/调度抖动
        // 会让 1s 窗口漏读刚领取的行（实测偶发 claimed=0）；写入后租约是"未来时间"，不会误命中旧行。
        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusSeconds(10);
        int claimed = mapper.claim(candidates, owner, properties.getL3().getJobTimeoutSeconds(), principal);
        if (claimed == 0) {
            return List.of();
        }
        List<MemoryExtractionJob> rows = mapper.selectClaimed(candidates, owner, since);
        log.info("[l3-outbox] claim: principal={}, owner={}, candidates={}, claimed={}", principal, owner,
                candidates.size(), rows.size());
        return rows;
    }

    /** 成功提交（过期 Worker 返回 false）。 */
    public boolean markDone(MemoryExtractionJob job, String owner) {
        boolean ok = mapper.markDone(job.getJobId(), owner, job.getGeneration()) == 1;
        if (!ok) {
            log.warn("[l3-outbox] 完成提交被拒绝（执行权已易主或任务已非 RUNNING）: jobId={}", job.getJobId());
        }
        return ok;
    }

    /**
     * 失败提交（过期 Worker 返回 false）。退避：attempts 越大等待越久；达 maxAttempts ⇒ DEAD。
     */
    public boolean markFailed(MemoryExtractionJob job, String owner, String errorCode) {
        int maxAttempts = Math.max(1, properties.getL3().getMaxAttempts());
        long backoff = backoffSeconds(job.getAttempts() == null ? 1 : job.getAttempts());
        boolean ok = mapper.markFailed(job.getJobId(), owner, job.getGeneration(), safeError(errorCode),
                maxAttempts, backoff) == 1;
        if (!ok) {
            log.warn("[l3-outbox] 失败提交被拒绝（执行权已易主或任务已非 RUNNING）: jobId={}", job.getJobId());
        }
        return ok;
    }

    /**
     * 崩溃恢复：租约过期的 RUNNING 回到 PENDING（或 attempts 超限 ⇒ DEAD）。
     * <p>可由定时任务或启动钩子调用；本方法幂等。
     */
    @Transactional
    public int recoverExpired() {
        int recovered = mapper.recoverExpired(Math.max(1, properties.getL3().getMaxAttempts()));
        if (recovered > 0) {
            log.warn("[l3-outbox] 恢复过期任务数={}", recovered);
        }
        return recovered;
    }

    /** 指数退避（上限 10 分钟），不含随机抖动（便于测试断言）。 */
    static long backoffSeconds(int attempts) {
        long base = 5L;
        long cap = 600L;
        long value = base * (1L << Math.min(Math.max(attempts - 1, 0), 7));
        return Math.min(value, cap);
    }

    private static String safeError(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return "UNKNOWN";
        }
        // 只保留错误码（避免把堆栈/DB 细节写进任务表）
        String cleaned = errorCode.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("幂等键计算失败", e);
        }
    }
}
