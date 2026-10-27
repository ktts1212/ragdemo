package com.example.ragdemo.workflow.runtime;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.mapper.DocumentsMapper;
import com.example.ragdemo.mapper.IngestionTaskMapper;
import com.example.ragdemo.model.entity.IngestionTask;
import com.example.ragdemo.model.entity.KnowledgeDocument;

import lombok.extern.slf4j.Slf4j;

/**
 * 自动恢复调度器（Step 5 §5）：进程重启后无需人工调用 {@code resume}，
 * 周期扫描"可恢复候选"并经统一入口收敛。
 * <p>
 * 候选判据与租约抢占判据<b>完全一致</b>（{@code pending} / 退避到期的 {@code retry_wait} /
 * 租约过期的 {@code running}）⇒ <b>不会把当前存活的执行者抢掉</b>；
 * {@code waiting}（等待人工确认）与终态不参与扫描。
 * <p>
 * 每个候选都交给 {@link WorkflowReconciliationService#recoverOrExecute}：
 * 无 checkpoint ⇒ 首次执行；Graph 未 END ⇒ resume 补完；Graph 已 END ⇒ 只对齐 task 状态；已 failed ⇒ 不重跑。
 * 并发上限用 {@link Semaphore} 控制，避免一轮扫描同时拉起过多图执行。
 * <p>
 * 由 {@code IngestionGraphConfig} 以 {@code @Bean(initMethod="start", destroyMethod="stop")} 注册，
 * 且默认 <b>关闭</b>（{@code rag.workflow.ingestion.recovery-enabled=true} 才装配）。
 */
@Slf4j
public class WorkflowRecoveryScheduler {

    private final IngestionTaskMapper ingestionTaskMapper;
    private final DocumentsMapper documentsMapper;
    private final WorkflowReconciliationService reconciliation;
    private final long intervalMs;
    private final int batchSize;
    private final Semaphore concurrency;
    private final ScheduledExecutorService scanner;
    private final ExecutorService workers;
    private volatile boolean running;

    public WorkflowRecoveryScheduler(IngestionTaskMapper ingestionTaskMapper,
            DocumentsMapper documentsMapper, WorkflowReconciliationService reconciliation,
            RagProperties properties) {
        this.ingestionTaskMapper = ingestionTaskMapper;
        this.documentsMapper = documentsMapper;
        this.reconciliation = reconciliation;
        this.intervalMs = Math.max(200L, properties.getWorkflow().getIngestion().getRecoveryIntervalMs());
        this.batchSize = Math.max(1, properties.getWorkflow().getIngestion().getRecoveryBatchSize());
        int maxConcurrency = Math.max(1, properties.getWorkflow().getIngestion().getRecoveryMaxConcurrency());
        this.concurrency = new Semaphore(maxConcurrency);
        this.scanner = Executors.newSingleThreadScheduledExecutor(
                r -> daemon(r, "ingestion-recovery-scanner"));
        this.workers = Executors.newFixedThreadPool(maxConcurrency,
                r -> daemon(r, "ingestion-recovery-worker"));
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** 启动周期扫描（由 Bean 的 initMethod 调用）。 */
    public void start() {
        running = true;
        scanner.scheduleWithFixedDelay(this::scanOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("自动恢复调度器已启动: intervalMs={}, batchSize={}", intervalMs, batchSize);
    }

    public void stop() {
        running = false;
        scanner.shutdownNow();
        workers.shutdown();
        log.info("自动恢复调度器已停止");
    }

    /**
     * 执行一轮扫描（同步返回本轮提交的候选数），便于测试直接调用。
     */
    public int scanOnce() {
        if (!running) {
            return 0;
        }
        List<IngestionTask> candidates;
        try {
            candidates = ingestionTaskMapper.findRecoverable(batchSize);
        } catch (Exception e) {
            log.warn("恢复扫描查询失败: {}", e.toString());
            return 0;
        }
        int submitted = 0;
        for (IngestionTask task : candidates) {
            if (!concurrency.tryAcquire()) {
                log.debug("恢复并发已达上限，本轮跳过剩余候选");
                break;
            }
            workers.submit(() -> {
                try {
                    recoverOne(task);
                } catch (Exception e) {
                    log.warn("恢复任务异常（已由 Coordinator 分类处理）: taskId={}, err={}",
                            task.getId(), e.toString());
                } finally {
                    concurrency.release();
                }
            });
            submitted++;
        }
        return submitted;
    }

    private void recoverOne(IngestionTask task) {
        KnowledgeDocument document = documentsMapper.selectById(task.getDocumentId());
        if (document == null) {
            log.warn("恢复扫描：文档不存在，跳过 taskId={}, documentId={}",
                    task.getId(), task.getDocumentId());
            return;
        }
        WorkflowReconciliationService.Result result = reconciliation.recoverOrExecute(document, task.getId());
        log.info("恢复扫描处理完成: taskId={}, outcome={}, detail={}",
                task.getId(), result.outcome(), result.detail());
    }
}
