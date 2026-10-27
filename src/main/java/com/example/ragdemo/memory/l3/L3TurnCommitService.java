package com.example.ragdemo.memory.l3;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ragdemo.memory.ConversationMemoryService;

import lombok.extern.slf4j.Slf4j;

/**
 * 传统 RAG 路径的"消息 + Outbox"原子提交（Memory V1 · Phase 2A）。
 * <p><b>事务边界</b>：本类的 {@link #commitTurnAndEnqueue} 是 {@code @Transactional}，
 * `saveTurn`（消息写入）与 Outbox 入队**在同一事务**内：任一失败则整体回滚，
 * 不会出现"消息已保存但任务丢失"或"任务存在但消息不存在"。
 * <p>注意：调用方必须是**跨 Bean 调用**（Spring 代理），否则 {@code @Transactional} 不生效。
 * <p>本方法内**不调用任何 LLM**（提取在独立 Worker 中异步执行）。
 */
@Slf4j
@Service
public class L3TurnCommitService {

    private final ConversationMemoryService memoryService;
    private final MemoryExtractionJobService outbox;

    public L3TurnCommitService(ConversationMemoryService memoryService, MemoryExtractionJobService outbox) {
        this.memoryService = memoryService;
        this.outbox = outbox;
    }

    /**
     * 原子提交一轮问答并（在 L3 开启时）入队提取任务。
     *
     * @param sourceRunId Agent 路径传入 runId；传统 RAG 传 null
     */
    @Transactional
    public void commitTurnAndEnqueue(String conversationId, String question, String answer, String sourceRunId) {
        memoryService.saveTurn(conversationId, question, answer);
        boolean enqueued = outbox.enqueueForLatestTurn(conversationId, sourceRunId);
        log.debug("[l3-outbox] 原子提交完成: conversationId={}, enqueued={}", conversationId, enqueued);
    }
}
