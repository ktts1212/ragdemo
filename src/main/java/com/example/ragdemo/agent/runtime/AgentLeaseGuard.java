package com.example.ragdemo.agent.runtime;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.ragdemo.agent.api.AgentRequestException;

import lombok.extern.slf4j.Slf4j;

/**
 * 单次 Run 的<b>执行权守卫 + 有界心跳</b>（Step 4E-4）。
 * <p>
 * 设计约束（与交接单 §5.4 一致）：
 * <ul>
 *   <li><b>每次 Run 独立实例</b>：绑定 runId/owner/fencingToken/generation；由 Graph 工厂在 {@code compile}
 *       时注入节点 ⇒ 不使用全局单例、不使用 ThreadLocal；</li>
 *   <li><b>不写入 Checkpoint</b>：Lease 是执行期权限，绝不被序列化进图状态（状态里只有可恢复的业务数据）；</li>
 *   <li><b>Resume 重新构造</b>：新执行者重新 {@code tryAcquireLease} 后重建守卫（旧守卫随之 close）；</li>
 *   <li><b>有界</b>：心跳间隔须明显小于 TTL；连续续租失败达上限即判定失权并<b>停止心跳</b>，不无限重试。</li>
 * </ul>
 * 强制点：{@link #assertHeld(String)} —— 内部做一次 owner+token 保护的 {@code renewLease} 校验，
 * 因此"租约仍在有效期内但已被他人接管"也会被识别为失权并抛 409 {@code AGENT_LEASE_LOST}。
 * <p>
 * 已知边界：节点返回与 Saver 写入 Checkpoint 之间无法 fencing（Saver 无 owner 列）⇒ 仅提供
 * <b>受约束的单活恢复</b>，不宣称严格多实例安全。
 */
@Slf4j
public final class AgentLeaseGuard implements AutoCloseable {

    /** 续租<b>异常</b>（状态未知）的连续上限：超过即判失权，避免无限重试 DB。 */
    private static final int MAX_CONSECUTIVE_RENEW_ERRORS = 3;

    private final AgentRunRegistry registry;
    private final AgentRunRegistry.Lease lease;
    private final long ttlSeconds;
    private final long heartbeatIntervalSeconds;

    private final AtomicBoolean lost = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 续租异常导致的"执行权状态未知"：验证成功前禁止新的 LLM/Tool 调用。 */
    private final AtomicBoolean authorityUnverified = new AtomicBoolean(false);
    private final AtomicInteger renewErrors = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;

    private AgentLeaseGuard(AgentRunRegistry registry, AgentRunRegistry.Lease lease, long ttlSeconds,
            long heartbeatIntervalSeconds) {
        this.registry = registry;
        this.lease = lease;
        this.ttlSeconds = ttlSeconds;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-lease-heartbeat-" + lease.runId());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 建立守卫并（可选）启动心跳。
     *
     * @param ttlSeconds             租约 TTL（秒）
     * @param heartbeatIntervalSeconds 心跳间隔（秒）；{@code <= 0} 表示不启动心跳
     */
    public static AgentLeaseGuard heartbeat(AgentRunRegistry registry, AgentRunRegistry.Lease lease,
            long ttlSeconds, long heartbeatIntervalSeconds) {
        long interval = heartbeatIntervalSeconds;
        if (interval > 0 && ttlSeconds > 0 && interval * 2 >= ttlSeconds) {
            // 心跳必须明显小于 TTL，否则调小到 1/4（防御性收敛，不静默接受错误配置）
            interval = Math.max(1, ttlSeconds / 4);
            log.warn("[agent-lease] 心跳间隔过大，已收敛为 TTL/4: runId={}, interval={}s", lease.runId(), interval);
        }
        AgentLeaseGuard guard = new AgentLeaseGuard(registry, lease, ttlSeconds, interval);
        guard.startHeartbeat();
        return guard;
    }

    private void startHeartbeat() {
        if (heartbeatIntervalSeconds <= 0) {
            log.info("[agent-lease] 未启动心跳（interval<=0）: runId={}", lease.runId());
            return;
        }
        heartbeatTask = scheduler.scheduleWithFixedDelay(this::renewTick, heartbeatIntervalSeconds,
                heartbeatIntervalSeconds, TimeUnit.SECONDS);
        log.info("[agent-lease] 心跳已启动: runId={}, owner={}, token={}, interval={}s, ttl={}s", lease.runId(),
                lease.owner(), lease.fencingToken(), heartbeatIntervalSeconds, ttlSeconds);
    }

    /**
     * 心跳一次。两种失败被<b>区别对待</b>（Step 4E-4B）：
     * <ul>
     *   <li>{@code renewLease} 返回 <b>false</b> ⇒ <b>确定失权</b>（owner/token 不匹配或已被接管）：
     *       立即 {@code lost=true} 并<b>停止心跳</b>，不做任何重试；</li>
     *   <li>{@code renewLease} 抛<b>异常</b> ⇒ 状态未知（DB 不可达等）：有限重试（上限
     *       {@value #MAX_CONSECUTIVE_RENEW_ERRORS}），期间置 {@code authorityUnverified=true}
     *       ⇒ {@link #assertHeld(String)} 在重新验证成功前拒绝启动新的 LLM/Tool 调用。</li>
     * </ul>
     */
    private void renewTick() {
        if (closed.get() || lost.get()) {
            return;
        }
        try {
            if (registry.renewLease(lease, ttlSeconds)) {
                renewErrors.set(0);
                authorityUnverified.set(false);
                return;
            }
            // 确定失权：不重试、不掩盖
            markLost("心跳续租返回 0（执行权已易主或租约失效）");
            stopHeartbeat();
        } catch (RuntimeException e) {
            authorityUnverified.set(true);
            int errors = renewErrors.incrementAndGet();
            log.warn("[agent-lease] 心跳续租异常（{}/{}，状态未知，禁止新的模型/工具调用）: runId={}, owner={}, "
                    + "token={}, type={}", errors, MAX_CONSECUTIVE_RENEW_ERRORS, lease.runId(), lease.owner(),
                    lease.fencingToken(), e.getClass().getSimpleName());
            if (errors >= MAX_CONSECUTIVE_RENEW_ERRORS) {
                markLost("心跳续租连续异常，无法确认执行权");
                stopHeartbeat();
            }
        }
    }

    private void markLost(String reason) {
        if (lost.compareAndSet(false, true)) {
            log.error("[agent-lease] 本执行者已失去执行权: runId={}, owner={}, token={}, reason={}", lease.runId(),
                    lease.owner(), lease.fencingToken(), reason);
        }
    }

    public boolean isLost() {
        return lost.get();
    }

    public String runId() {
        return lease.runId();
    }

    public String owner() {
        return lease.owner();
    }

    public long fencingToken() {
        return lease.fencingToken();
    }

    public int generation() {
        return lease.generation();
    }

    /**
     * 节点入口 / 结果处理前的执行权强制校验：失权即抛 409 {@code AGENT_LEASE_LOST}。
     * <p>
     * 该方法<b>必须</b>在任何"会启动新工作"的代码之前调用，并且不得被 ToolError 之类的
     * {@code RuntimeException} 捕获块吞掉。
     */
    public void assertHeld(String where) {
        if (lost.get()) {
            throw leaseLost(where, "本地已判定失去执行权");
        }
        if (closed.get()) {
            throw leaseLost(where, "本执行的权利窗口已关闭");
        }
        if (authorityUnverified.get()) {
            log.warn("[agent-lease] 执行权状态未知（心跳曾异常），重新验证后才允许继续: runId={}, where={}",
                    lease.runId(), where);
        }
        try {
            if (!registry.renewLease(lease, ttlSeconds)) {
                // 确定失权
                markLost("节点守卫续租返回 0: " + where);
                throw leaseLost(where, "执行权已易主或租约失效");
            }
        } catch (AgentRequestException own) {
            throw own;
        } catch (RuntimeException e) {
            // 状态未知：拒绝继续（不把"未知"当作"可用"）
            authorityUnverified.set(true);
            renewErrors.incrementAndGet();
            log.warn("[agent-lease] 执行权无法确认，拒绝继续: runId={}, where={}, type={}", lease.runId(), where,
                    e.getClass().getSimpleName());
            throw new AgentRequestException(409, "AGENT_LEASE_UNVERIFIED",
                    "执行权暂时无法确认（" + where + "）：数据库续租异常 " + e.getClass().getSimpleName()
                            + "；在验证成功前不启动新的模型/工具调用");
        }
        // 验证成功
        authorityUnverified.set(false);
        renewErrors.set(0);
    }

    private AgentRequestException leaseLost(String where, String reason) {
        log.warn("[agent-lease] 拒绝继续执行: runId={}, where={}, reason={}", lease.runId(), where, reason);
        return new AgentRequestException(409, "AGENT_LEASE_LOST",
                "执行权已失效（节点：" + where + "）：" + reason + "；本次执行不再启动新的模型/工具调用或提交结果");
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    /** 结束执行：停止心跳并释放线程（成功后不再续租，租约由终态写入清除或自然过期）。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopHeartbeat();
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("[agent-lease] 心跳线程未在 2s 内结束: runId={}", lease.runId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[agent-lease] 守卫已关闭（心跳停止）: runId={}, lost={}", lease.runId(), lost.get());
    }
}
