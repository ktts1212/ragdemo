package com.example.ragdemo.agent.runtime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ragdemo.memory.ConversationMemoryService;

import lombok.extern.slf4j.Slf4j;

/**
 * 会话记忆的<b>原子提交</b>（Step 4 §7.1）。
 * <p>
 * 修复的崩溃窗口：若"先单独提交 {@code memory_committed=true}，再单独提交 {@code saveTurn()}"，
 * 两次提交之间进程崩溃 ⇒ 该轮记忆永久丢失（标记已提交、消息却不存在）。
 * <p>
 * 正确做法：<b>抢占令牌与写入消息处于同一个数据库事务</b>：
 * <ol>
     *   <li>该 Run 的记忆提交令牌（<b>owner + token 保护</b>，只有把 false 翻成 true 的那次返回 true）；</li>
 *   <li>同事务内 {@link ConversationMemoryService#saveTurn} 写入该轮问答；</li>
 *   <li>任一步失败 ⇒ 整个事务回滚 ⇒ {@code memory_committed} 回到 false，可安全补偿重做。</li>
 * </ol>
 * <p>
 * 事务边界注意事项：
 * <ul>
 *   <li>本方法是事务边界，调用方必须通过 Spring 代理调用它（本类内部自调用会使 {@code @Transactional} 失效）；</li>
 *   <li>{@code AgentRunRegistry#claimMemoryCommit} 与 {@code saveTurn} 都<b>没有</b> REQUIRES_NEW
 *       ⇒ 它们加入本事务，不会提前提交；</li>
 *   <li>同一个 Run 只会成功提交一次 ⇒ 重复恢复不会生成重复会话记忆。</li>
 * </ul>
 */
@Slf4j
@Service
public class AgentMemoryCommitter {

    private final AgentRunRegistry registry;
    private final ConversationMemoryService memoryService;
    private final com.example.ragdemo.memory.l3.MemoryExtractionJobService l3Outbox;

    public AgentMemoryCommitter(AgentRunRegistry registry, ConversationMemoryService memoryService,
            com.example.ragdemo.memory.l3.MemoryExtractionJobService l3Outbox) {
        this.registry = registry;
        this.memoryService = memoryService;
        this.l3Outbox = l3Outbox;
    }

    /**
     * 原子提交该 Run 的会话轮次；已提交过则不做任何写入。
     * <p>
     * <b>执行权约束（Step 4E-2）</b>：必须传入当前 {@link AgentRunRegistry.Lease}；令牌是
     * {@code owner + fencing_token} 保护的，因此<b>失去执行权的旧执行者拿不到令牌</b>，
     * 不会替新执行者写会话记忆。
     *
     * @param lease 当前执行权（不得为 {@code null}）
     * @return true = 本次真正写入；false = 之前已提交或执行权已易主（幂等，未重复写）
     */
    @Transactional
    public boolean commitTurnOnce(AgentRunRegistry.Lease lease, String conversationId, String question,
            String answer) {
        if (lease == null || conversationId == null) {
            log.warn("[agent-memory] 拒绝提交（缺少执行权或会话）: conversationId={}", conversationId);
            return false;
        }
        if (!registry.claimMemoryCommit(lease)) {
            log.debug("[agent-memory] 该 Run 的记忆已提交过或执行权已易主，跳过: runId={}", lease.runId());
            return false;
        }
        memoryService.saveTurn(conversationId, question, answer);
        // Phase 2A：L3 提取任务与消息**同一事务**入队（L3 默认关闭时直接返回 false，不建任务）。
        // 注意：这里只入队，**不调用 LLM**；失败会随本事务回滚，不会出现"消息在、任务丢"。
        l3Outbox.enqueueForLatestTurn(conversationId, lease.runId());
        log.info("[agent-memory] 会话轮次已原子提交: runId={}, conversationId={}", lease.runId(), conversationId);
        return true;
    }
}
