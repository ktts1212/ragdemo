package com.example.ragdemo.memory.l3;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ragdemo.mapper.ConversationMessageMapper;
import com.example.ragdemo.mapper.MemoryExtractionJobMapper;
import com.example.ragdemo.model.entity.ConversationMessage;
import com.example.ragdemo.model.entity.MemoryExtractionJob;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

/**
 * L3 记忆提取 Worker（Memory V1 · Phase 2B）：把 Outbox 任务变成长期记忆。
 *
 * <p><b>执行顺序（关键）</b>：
 * <ol>
 *   <li>读**已提交的用户消息**（角色必须 user、会话必须与任务一致；否则不调用模型，任务按失败重试，绝不无来源写入）；</li>
 *   <li>**事务外**调用 LLM 提取候选（{@link MemoryExtractor}）；</li>
 *   <li>**事务外**执行代码策略（{@link MemoryPolicy}：类型/长度/敏感信息/注入/低价值/置信度）；</li>
 *   <li>**事务外**计算 embedding（策略 A：失败即任务失败重试，**绝不**写入"无向量却算完成"的记忆）；</li>
 *   <li>**短事务**内提交：先校验 {@code status=RUNNING + lease_owner + generation + 租约未过期}，
 *       再逐条 {@link MemoryConsolidator#apply}（槽位/版本/冲突），最后 {@code markDone}；
 *       任一守卫失败 ⇒ 抛异常**整体回滚**，不留下半套记忆。</li>
 * </ol>
 *
 * <p><b>边界</b>：principal 只来自任务行（入队时由服务端解析），scope 固定为 V1 的全局作用域；
 * 本类不接受任何来自模型/工具输出的身份或作用域。
 */
@Slf4j
@Service
public class MemoryJobProcessor {

    /** 与 {@code rag.embedding.dimension} / {@code sql/init.sql} 保持一致（bge-m3）。 */
    public static final int EMBEDDING_DIMENSION = 1024;

    /** 本实例的租约持有者标识（跨实例唯一，避免互相覆盖）。 */
    private final String owner = "l3-worker-" + UUID.randomUUID().toString().substring(0, 8);

    private final MemoryExtractionJobService outbox;
    private final MemoryExtractor extractor;
    private final MemoryPolicy policy;
    private final MemoryConsolidator consolidator;
    private final EmbeddingModel embeddingModel;
    private final ConversationMessageMapper messageMapper;
    private final MemoryExtractionJobMapper jobMapper;
    private final MemoryProperties properties;
    private final L3PrincipalResolver principalResolver;
    private final TransactionTemplate transactionTemplate;

    /** Phase 4B：故障注入（生产不装配；测试子进程用它做确定性 halt/抛异常）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryJobFaultHook faultHook;

    private void fault(String point) {
        if (faultHook != null) {
            faultHook.at(point);
        }
    }

    /** 租约丢失（迟到 Worker）：必须回滚，不得提交任何记忆。 */
    static class LeaseLostException extends RuntimeException {
        LeaseLostException(String message) {
            super(message);
        }
    }

    public MemoryJobProcessor(MemoryExtractionJobService outbox, MemoryExtractor extractor, MemoryPolicy policy,
            MemoryConsolidator consolidator, EmbeddingModel embeddingModel,
            ConversationMessageMapper messageMapper, MemoryExtractionJobMapper jobMapper,
            MemoryProperties properties, L3PrincipalResolver principalResolver,
            TransactionTemplate transactionTemplate) {
        this.outbox = outbox;
        this.extractor = extractor;
        this.policy = policy;
        this.consolidator = consolidator;
        this.embeddingModel = embeddingModel;
        this.messageMapper = messageMapper;
        this.jobMapper = jobMapper;
        this.properties = properties;
        this.principalResolver = principalResolver;
        this.transactionTemplate = transactionTemplate;
    }

    public String owner() {
        return owner;
    }

    /**
     * 处理一批任务：先恢复过期租约，再领取并逐个处理。
     *
     * @return 成功完成（DONE）的任务数
     */
    public int processBatch(int limit) {
        if (!principalResolver.available()) {
            return 0; // L3 关闭：不领取任何任务（现有行为不变）
        }
        outbox.recoverExpired();
        List<MemoryExtractionJob> jobs = outbox.claimPending(owner, limit);
        if (jobs.isEmpty()) {
            return 0;
        }
        int done = 0;
        for (MemoryExtractionJob job : jobs) {
            try {
                if (process(job)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.error("[l3-worker] 任务处理异常: jobId={}", job.getJobId(), e);
                outbox.markFailed(job, owner, "WORKER_ERROR");
            }
        }
        log.info("[l3-worker] 批次完成: owner={}, claimed={}, done={}", owner, jobs.size(), done);
        return done;
    }

    /**
     * 处理单个**已领取**的任务。返回 true = DONE（含"无候选"的 DONE(empty)）。
     * <p>租约丢失或失败 ⇒ 返回 false，且**不写入**任何长期记忆。
     */
    public boolean process(MemoryExtractionJob job) {
        if (job == null || job.getJobId() == null) {
            return false;
        }
        fault(MemoryJobFaultHook.AFTER_CLAIM);
        String principalId = job.getPrincipalId();
        if (principalId == null || principalId.isBlank()
                || !principalId.equals(principalResolver.principal())) {
            // 任务行的 principal 与当前服务端身份不一致 ⇒ 拒绝执行（不跨身份写记忆）
            outbox.markFailed(job, owner, "PRINCIPAL_MISMATCH");
            return false;
        }

        String sourceText = loadCommittedUserMessage(job);
        if (sourceText == null) {
            outbox.markFailed(job, owner, "SOURCE_MESSAGE_NOT_FOUND");
            return false;
        }

        // ① LLM 提取（事务外）
        List<MemoryCandidate> candidates;
        try {
            candidates = extractor.extract(sourceText);
        } catch (MemoryExtractor.MemoryExtractionException e) {
            log.warn("[l3-worker] 提取失败（可重试）: jobId={}, err={}", job.getJobId(), e.getMessage());
            outbox.markFailed(job, owner, "EXTRACTION_FAILED");
            return false;
        }
        fault(MemoryJobFaultHook.AFTER_EXTRACT);

        // ② 代码策略（模型无权绕过）
        List<MemoryCandidate> allowed = new ArrayList<>(candidates.size());
        for (MemoryCandidate candidate : candidates) {
            MemoryPolicy.Result verdict = policy.evaluate(candidate, sourceText);
            if (verdict.decision() == MemoryPolicy.Decision.ALLOW) {
                allowed.add(candidate);
            } else {
                log.info("[l3-worker] 候选被策略拒绝: jobId={}, code={}, type={}, subject={}", job.getJobId(),
                        verdict.code(), candidate.type(), candidate.subject());
            }
        }
        if (allowed.isEmpty()) {
            boolean ok = outbox.markDone(job, owner);
            log.info("[l3-worker] 无有效候选 ⇒ DONE(empty): jobId={}, done={}", job.getJobId(), ok);
            return ok;
        }

        // ③ Embedding（事务外；策略 A）
        List<float[]> vectors = new ArrayList<>(allowed.size());
        for (MemoryCandidate candidate : allowed) {
            float[] vector = embed(candidate, job);
            if (vector == null) {
                return false;
            }
            vectors.add(vector);
        }
        fault(MemoryJobFaultHook.AFTER_EMBED);

        // ④ 短事务提交
        return commit(job, principalId, allowed, vectors);
    }

    /** 读取来源消息；必须是**本会话内的 user 消息**，否则返回 null（不调用模型）。 */
    private String loadCommittedUserMessage(MemoryExtractionJob job) {
        String messageId = job.getSourceMessageId();
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        ConversationMessage message;
        try {
            message = messageMapper.selectById(Long.valueOf(messageId));
        } catch (NumberFormatException e) {
            return null;
        }
        if (message == null) {
            log.warn("[l3-worker] 来源消息不存在: jobId={}, messageId={}", job.getJobId(), messageId);
            return null;
        }
        if (!"user".equalsIgnoreCase(message.getRole())) {
            log.warn("[l3-worker] 来源消息不是 user 消息（拒绝提取）: jobId={}, role={}", job.getJobId(),
                    message.getRole());
            return null;
        }
        if (!Objects.equals(job.getConversationId(), message.getConversationId())) {
            log.warn("[l3-worker] 来源消息与会话不匹配: jobId={}", job.getJobId());
            return null;
        }
        String content = message.getContent();
        return content == null || content.isBlank() ? null : content;
    }

    private float[] embed(MemoryCandidate candidate, MemoryExtractionJob job) {
        try {
            Response<dev.langchain4j.data.embedding.Embedding> response = embeddingModel.embed(candidate.content());
            dev.langchain4j.data.embedding.Embedding embedding = response == null ? null : response.content();
            float[] vector = embedding == null ? null : embedding.vector();
            if (vector == null || vector.length != EMBEDDING_DIMENSION) {
                log.warn("[l3-worker] embedding 维度不符（期望 {}）: jobId={}", EMBEDDING_DIMENSION, job.getJobId());
                outbox.markFailed(job, owner, "EMBEDDING_DIMENSION_MISMATCH");
                return null;
            }
            return vector;
        } catch (RuntimeException e) {
            log.warn("[l3-worker] embedding 失败（策略 A：任务失败重试，不写无向量记忆）: jobId={}, err={}",
                    job.getJobId(), e.getClass().getSimpleName());
            outbox.markFailed(job, owner, "EMBEDDING_FAILED");
            return null;
        }
    }

    private boolean commit(MemoryExtractionJob job, String principalId, List<MemoryCandidate> allowed,
            List<float[]> vectors) {
        try {
            Boolean committed = transactionTemplate.execute(status -> {
                MemoryExtractionJob current = jobMapper.selectById(job.getJobId());
                assertOwned(job, current);
                fault(MemoryJobFaultHook.TX_BEFORE_INSERT);
                for (int i = 0; i < allowed.size(); i++) {
                    consolidator.apply(principalId, null, allowed.get(i), vectors.get(i),
                            job.getConversationId(), job.getSourceMessageId(), job.getSourceRunId());
                }
                fault(MemoryJobFaultHook.TX_AFTER_INSERT);
                if (!outbox.markDone(job, owner)) {
                    throw new LeaseLostException("markDone 被拒绝（owner/generation 已变化）: jobId=" + job.getJobId());
                }
                return Boolean.TRUE;
            });
            fault(MemoryJobFaultHook.AFTER_COMMIT);
            log.info("[l3-worker] 任务完成: jobId={}, 写入候选数={}", job.getJobId(), allowed.size());
            return Boolean.TRUE.equals(committed);
        } catch (LeaseLostException e) {
            log.warn("[l3-worker] 租约丢失，已回滚本次写入: jobId={}, reason={}", job.getJobId(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.error("[l3-worker] 提交失败（已回滚）: jobId={}", job.getJobId(), e);
            outbox.markFailed(job, owner, "COMMIT_FAILED");
            return false;
        }
    }

    /** 提交前守卫：仍是本次领取（RUNNING + owner + generation）且租约未过期。 */
    private void assertOwned(MemoryExtractionJob job, MemoryExtractionJob current) {
        if (current == null
                || !"RUNNING".equals(current.getStatus())
                || !Objects.equals(owner, current.getLeaseOwner())
                || !Objects.equals(job.getGeneration(), current.getGeneration())) {
            throw new LeaseLostException("任务执行权已易主: jobId=" + job.getJobId());
        }
        LocalDateTime expiresAt = current.getLeaseExpiresAt();
        if (expiresAt == null || expiresAt.isBefore(LocalDateTime.now())) {
            throw new LeaseLostException("租约已过期: jobId=" + job.getJobId());
        }
    }
}
