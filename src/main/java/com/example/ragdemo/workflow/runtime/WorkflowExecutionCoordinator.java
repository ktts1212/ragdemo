package com.example.ragdemo.workflow.runtime;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.mapper.DocumentsMapper;
import com.example.ragdemo.model.entity.KnowledgeDocument;
import com.example.ragdemo.workflow.ingestion.IngestionState;
import com.example.ragdemo.workflow.ingestion.IngestionWorkflowRunner;
import com.example.ragdemo.workflow.runtime.TaskLeaseService.Lease;

import lombok.extern.slf4j.Slf4j;

/**
 * 统一的图执行入口（Step 5 §9）：<b>Kafka Consumer 与恢复调度器都必须经由此处</b>，
 * 不允许绕过它直接调用 {@link IngestionWorkflowRunner}。
 * <p>
 * 职责：
 * <ol>
 *   <li>原子抢占任务执行权（{@link TaskLeaseService#tryAcquire}）——未抢到则直接返回，不启动 Graph；</li>
 *   <li>把 {@code owner + fencingToken} 注入 Graph State，使 {@code persist}/{@code finalize}
 *       的写入受同事务 fencing 保护；</li>
 *   <li>按需选择 {@code args} / {@code resume}（由 Runner 依据 checkpoint 决定）；</li>
 *   <li>失败分类：永久 → {@code failed}；瞬时 → {@code retry_wait} + 退避；
 *       <b>执行权丢失 → 不写任务状态</b>（交给新持有者，避免破坏其状态机）。</li>
 * </ol>
 * <b>注意</b>：本类<b>不</b>标 {@code @Service}，只在 {@code IngestionGraphConfig}
 * （受 {@code rag.workflow.ingestion.enabled} 约束）里注册为 Bean —— 否则开关关闭时
 * 会因缺少 {@link IngestionWorkflowRunner} 而启动失败。
 */
@Slf4j
public class WorkflowExecutionCoordinator {

    private final TaskLeaseService leaseService;
    private final IngestionWorkflowRunner ingestionWorkflowRunner;
    private final DocumentsMapper documentsMapper;
    private final FailureClassifier failureClassifier;
    private final long leaseTtlSeconds;
    private final long retryBackoffSeconds;

    /** 本进程内所有执行共用的 Worker ID（同进程内可区分不同 Worker 进程）。 */
    private final String workerId = TaskLeaseService.newWorkerId("worker");

    /** 租约续租调度器（守护线程，单线程足够）。 */
    private final ScheduledExecutorService leaseRenewer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ingestion-lease-renewer");
        thread.setDaemon(true);
        return thread;
    });

    public WorkflowExecutionCoordinator(TaskLeaseService leaseService,
            IngestionWorkflowRunner ingestionWorkflowRunner, DocumentsMapper documentsMapper,
            FailureClassifier failureClassifier, RagProperties ragProperties) {
        this.leaseService = leaseService;
        this.ingestionWorkflowRunner = ingestionWorkflowRunner;
        this.documentsMapper = documentsMapper;
        this.failureClassifier = failureClassifier;
        this.leaseTtlSeconds = ragProperties.getWorkflow().getIngestion().getLeaseTtlSeconds();
        this.retryBackoffSeconds = ragProperties.getWorkflow().getIngestion().getRetryBackoffSeconds();
    }

    public String workerId() {
        return workerId;
    }

    public long leaseTtlSeconds() {
        return leaseTtlSeconds;
    }

    /** 按 documentId 执行（供调度器使用）。 */
    public Optional<IngestionState> execute(long documentId, long taskId) {
        KnowledgeDocument document = documentsMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalStateException("ACADEMIC_DOWNLOAD_FAILED: 文档不存在 documentId=" + documentId);
        }
        return execute(document, taskId);
    }

    /**
     * 唯一运行入口。
     *
     * @return 未取得执行权时为空；否则返回图终态（异常会向上抛出，任务状态已按分类处理）
     */
    public Optional<IngestionState> execute(KnowledgeDocument document, long taskId) {
        Optional<Lease> acquired = leaseService.tryAcquire(taskId, workerId, leaseTtlSeconds);
        if (acquired.isEmpty()) {
            log.info("未取得执行权，跳过本次执行: taskId={}, worker={}", taskId, workerId);
            return Optional.empty();
        }
        Lease lease = acquired.get();
        try {
            IngestionState state = runGraphWithRenewal(document, taskId, lease);
            if ("FINISHED".equals(state.status())) {
                // finalize 节点已用 owner+token 标记成功；此处仅记录
                log.info("入库图完成: taskId={}, owner={}, token={}", taskId, lease.owner(),
                        lease.fencingToken());
                return Optional.of(state);
            }
            // 图未走到 END（例如被人工中断/状态未收敛）：不标成功，交给调度器继续
            log.warn("入库图未到达 END，转入待恢复: taskId={}, status={}", taskId, state.status());
            leaseService.markRetryWait(lease, "GRAPH_NOT_FINISHED", "status=" + state.status(),
                    retryBackoffSeconds);
            return Optional.of(state);
        } catch (RuntimeException e) {
            FailureClassifier.Result result = failureClassifier.classify(e);
            String message = brief(e);
            switch (result.kind()) {
                case LEASE_LOST -> log.warn("执行权已易主，本 Worker 不写任务状态: taskId={}, owner={}",
                        taskId, lease.owner());
                case PERMANENT -> leaseService.markFailed(lease, result.code(), message);
                case TRANSIENT -> leaseService.markRetryWait(lease, result.code(), message,
                        retryBackoffSeconds);
            }
            throw e;
        }
    }

    private static String brief(Throwable e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    /**
     * 执行图并在执行期间<b>周期性续租</b>（step5 §4.2：长 Marker 解析不得因超过初始 TTL 被误接管）。
     * <p>
     * 续租失败不直接中止运行 —— 真正的保护是 {@code persist}/{@code finalize} 里
     * <b>同事务的 owner+token 校验</b>：一旦执行权被接管，迟到写入会被 {@code FENCING_REJECTED} 拒绝。
     */
    private IngestionState runGraphWithRenewal(KnowledgeDocument document, long taskId, Lease lease) {
        long periodSeconds = Math.max(1L, leaseTtlSeconds / 3);
        ScheduledFuture<?> renewal = leaseRenewer.scheduleAtFixedRate(() -> {
            try {
                if (!leaseService.renew(lease, leaseTtlSeconds)) {
                    log.warn("续租失败（执行权可能已易主）: taskId={}, owner={}, token={}",
                            taskId, lease.owner(), lease.fencingToken());
                }
            } catch (Exception e) {
                log.warn("续租异常: taskId={}, err={}", taskId, e.toString());
            }
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS);
        try {
            return ingestionWorkflowRunner.startOrResume(document, taskId, lease)
                    .orElseThrow(() -> new IllegalStateException(
                            "INGESTION_STATE_MISMATCH: 图未返回状态 taskId=" + taskId));
        } finally {
            renewal.cancel(false);
        }
    }
}
