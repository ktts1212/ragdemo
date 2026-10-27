package com.example.ragdemo.workflow.runtime;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.ragdemo.mapper.IngestionTaskMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 任务租约服务（Step 5 §4）：原子抢占、续租、释放，以及 owner+token 保护的终态标记。
 * <p>
 * <b>设计原则</b>：
 * <ul>
 *   <li>过期判断全部交给数据库时钟（{@code NOW()}），不依赖应用机时钟；</li>
 *   <li>抢占是<b>单条条件 UPDATE</b>（CAS），并发下只有一个 Worker 成功；</li>
 *   <li>所有"标记状态"的更新都带 {@code lease_owner + fencing_token} 条件 ⇒
 *       失去租约的旧 Worker 的迟到写入会被拒绝；</li>
 *   <li>真正的<b>业务写入保护</b>不在本类，而在 {@code replaceChunksGuarded}
 *       的同一事务内（{@code SELECT ... FOR UPDATE}），见 {@link #leaseOf} 的说明。</li>
 * </ul>
 */
@Slf4j
@Service
public class TaskLeaseService {

    /** 默认租约 TTL（秒）：须大于单个节点的最长耗时（Marker 解析可能数十秒），靠续租覆盖。 */
    public static final long DEFAULT_TTL_SECONDS = 120L;

    private final IngestionTaskMapper ingestionTaskMapper;

    public TaskLeaseService(IngestionTaskMapper ingestionTaskMapper) {
        this.ingestionTaskMapper = ingestionTaskMapper;
    }

    /** 生成本次进程/执行的 Worker 唯一 ID。 */
    public static String newWorkerId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 一次成功抢占得到的执行权凭据。 */
    public record Lease(long taskId, String owner, long fencingToken) {
    }

    /**
     * 原子抢占任务执行权。
     *
     * @return 成功返回 {@link Lease}；失败返回 {@link Optional#empty()}（他人持有有效租约或状态不允许）
     */
    public Optional<Lease> tryAcquire(long taskId, String owner, long ttlSeconds) {
        int updated = ingestionTaskMapper.tryAcquire(taskId, owner, ttlSeconds);
        if (updated != 1) {
            log.info("任务抢占未成功（他人持有有效租约或状态不允许）: taskId={}, owner={}", taskId, owner);
            return Optional.empty();
        }
        Long token = ingestionTaskMapper.currentFencingToken(taskId);
        Lease lease = new Lease(taskId, owner, token == null ? -1L : token);
        log.info("任务抢占成功: taskId={}, owner={}, token={}", taskId, owner, lease.fencingToken());
        return Optional.of(lease);
    }

    public boolean renew(Lease lease, long ttlSeconds) {
        return ingestionTaskMapper.renewLease(lease.taskId(), lease.owner(), lease.fencingToken(), ttlSeconds) == 1;
    }

    public boolean release(Lease lease) {
        return ingestionTaskMapper.releaseLease(lease.taskId(), lease.owner(), lease.fencingToken()) == 1;
    }

    public boolean markSucceeded(Lease lease) {
        boolean ok = ingestionTaskMapper.markSucceededIfOwner(
                lease.taskId(), lease.owner(), lease.fencingToken()) == 1;
        if (!ok) {
            log.warn("标记成功被拒绝（执行权已易主）: taskId={}, owner={}, token={}",
                    lease.taskId(), lease.owner(), lease.fencingToken());
        }
        return ok;
    }

    /** 永久错误：进入 failed 终态，不再自动重试。 */
    public boolean markFailed(Lease lease, String errorCode, String message) {
        return ingestionTaskMapper.markFailedOrRetryIfOwner(lease.taskId(), lease.owner(),
                lease.fencingToken(), "failed", errorCode, message, 0L) == 1;
    }

    /** 瞬时错误：进入 retry_wait，等待退避后可被再次抢占。 */
    public boolean markRetryWait(Lease lease, String errorCode, String message, long backoffSeconds) {
        return ingestionTaskMapper.markFailedOrRetryIfOwner(lease.taskId(), lease.owner(),
                lease.fencingToken(), "retry_wait", errorCode, message, backoffSeconds) == 1;
    }

    /** 等待人工确认：进入 waiting，不被自动恢复抢占。 */
    public boolean markWaiting(Lease lease, String errorCode, String message) {
        return ingestionTaskMapper.markWaitingIfOwner(lease.taskId(), lease.owner(),
                lease.fencingToken(), errorCode, message) == 1;
    }

    /**
     * fencing 校验（同一事务内）：只有 owner + token + {@code status='running'} 同时匹配才返回非 null，
     * 并锁住该任务行。业务写入方（{@code ChunkPersistenceService.replaceChunksGuarded}）依赖本方法。
     */
    public Long lockOwnedToken(long taskId, String owner, long token) {
        return ingestionTaskMapper.lockOwnedToken(taskId, owner, token);
    }

    /** 便于日志/对账：返回当前 lease 快照（只读）。 */
    public Optional<Lease> leaseOf(long taskId) {
        Long token = ingestionTaskMapper.currentFencingToken(taskId);
        return token == null ? Optional.empty() : Optional.of(new Lease(taskId, null, token));
    }
}
