package com.example.ragdemo.memory.l3;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * L3 记忆 Worker 轮询器（Memory V1 · Phase 2B）。
 *
 * <p>默认**不启动**：需同时满足
 * <ul>
 *   <li>{@code rag.memory.l3.enabled=true}（可信身份已配置，{@link L3PrincipalResolver#available()} 为真）；</li>
 *   <li>{@code rag.memory.l3.worker-enabled=true}（本 Bean 才被创建）。</li>
 * </ul>
 * 双开关避免测试或无意启动时后台线程干扰现有行为。单守护线程、空闲退避，异常不杀死线程。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.memory.l3", name = "worker-enabled", havingValue = "true")
public class MemoryJobPoller {

    private final MemoryJobProcessor processor;
    private final MemoryProperties properties;
    private volatile boolean running = true;
    private Thread thread;

    public MemoryJobPoller(MemoryJobProcessor processor, MemoryProperties properties) {
        this.processor = processor;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.getL3().isEnabled()) {
            log.info("[l3-poller] L3 未启用，Worker 不启动");
            return;
        }
        thread = new Thread(this::loop, "l3-memory-worker");
        thread.setDaemon(true);
        thread.start();
        log.info("[l3-poller] Worker 已启动: owner={}, batch={}, idleMillis={}", processor.owner(),
                properties.getL3().getWorkerBatchSize(), properties.getL3().getWorkerIdleMillis());
    }

    private void loop() {
        int batch = Math.max(1, properties.getL3().getWorkerBatchSize());
        long idle = Math.max(100L, properties.getL3().getWorkerIdleMillis());
        while (running) {
            try {
                int done = processor.processBatch(batch);
                if (done == 0) {
                    Thread.sleep(idle);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 单批异常不影响后续轮询（任务本身由 Outbox 重试/DEAD 兜底）
                log.warn("[l3-poller] 轮询异常，{}ms 后继续: {}", idle, e.toString());
                try {
                    Thread.sleep(idle);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
