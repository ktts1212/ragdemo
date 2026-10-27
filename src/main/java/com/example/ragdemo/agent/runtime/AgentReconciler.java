package com.example.ragdemo.agent.runtime;

import org.springframework.stereotype.Service;

import com.example.ragdemo.agent.api.AgentRequestException;
import com.example.ragdemo.agent.graph.RagAgentState;
import com.example.ragdemo.agent.model.AgentFinishReason;
import com.example.ragdemo.model.entity.AgentRun;

import lombok.extern.slf4j.Slf4j;

/**
 * Graph 终态与传统持久层之间的<b>对账</b>（Step 4 §7.2/§7.3）。
 * <p>
 * Checkpoint、Run Registry、会话记忆三者不在同一事务里，因此存在两个必须补偿的窗口：
 * <ul>
 *   <li><b>窗口 A</b>：Graph 已到 END（Checkpoint 有终态），但记忆尚未提交
 *       ⇒ 读终态补交记忆，<b>绝不重跑 LLM/工具</b>；</li>
 *   <li><b>窗口 B</b>：记忆已提交，但 Registry 终态未更新
 *       ⇒ 只补终态，<b>绝不重复写记忆</b>（由 {@link AgentMemoryCommitter} 的令牌保证）。</li>
 * </ul>
 * 另外：<b>不把未完成/兜底结果伪装成正常完成</b> —— 只有 {@code ANSWERED} 才写入会话记忆；
 * {@code BUDGET_EXHAUSTED/LOOP_DETECTED/TOOL_FAILURE} 只补终态并如实记录 finishReason 到
 * {@code last_error_code}；{@code ERROR} 记为 FAILED。
 * <p>
 * 幂等性：可被反复调用，结果收敛（已完成 Run 直接返回 ALREADY_TERMINAL，不做任何写入）。
 */
@Slf4j
@Service
public class AgentReconciler {

    /** 对账动作（供调用方/测试断言）。 */
    public enum Action {
        /** 已完成（Registry 终态已确认）：不重跑、不重写 */
        ALREADY_TERMINAL,
        /** 尚无 Checkpoint：应安全执行首次初始化（不由本类执行） */
        INITIAL_START,
        /** 有未完成 Checkpoint：应由调用方 Resume Graph（本类不重跑） */
        RESUME_GRAPH,
        /** 窗口 A：Graph 已 END 且回答为 ANSWERED，已补交记忆并补终态 */
        COMPENSATE_MEMORY,
        /** 窗口 B：记忆已提交（或本轮无需写入），只补终态 */
        COMPLETE_TERMINAL_ONLY,
        /** Graph 已 END 但结果未完成：不写记忆，只补终态（如实记录未完成原因） */
        TERMINAL_INCOMPLETE
    }

    public record Reconciliation(Action action, String finishReason, boolean memoryWritten,
            boolean terminalUpdated, String detail) {
    }

    private final AgentRunRegistry registry;
    private final AgentMemoryCommitter memoryCommitter;

    public AgentReconciler(AgentRunRegistry registry, AgentMemoryCommitter memoryCommitter) {
        this.registry = registry;
        this.memoryCommitter = memoryCommitter;
    }

    /**
     * 依据 Registry 状态与 Checkpoint 终态做对账（可重复调用）。
     * <p>
     * <b>执行权约束（Step 4E-2）</b>：只有"Registry 已是终态"的 Run 允许在不持租约时直接返回
     * （该分支<b>零写入</b>）；其余所有会写入的分支都必须持有该 Run 的租约，否则抛
     * {@code AGENT_LEASE_REQUIRED} —— 生产代码中<b>不存在</b>绕过租约的写入路径。
     * 若写入过程中执行权已易主（owner/token 保护的 UPDATE 返回 0），则如实返回
     * {@code terminalUpdated=false}，不重试、不伪装成功（留待合法持有者补账）。
     *
     * @param run            已经过授权校验的 Run（kb/会话归属已核对）
     * @param checkpointState 该 Run 的 Checkpoint 状态；{@code null} 表示尚无 Checkpoint
     * @param lease          当前执行权；{@code null} 仅允许用于"已终态"的只读分支
     */
    public Reconciliation reconcile(AgentRun run, RagAgentState checkpointState, AgentRunRegistry.Lease lease) {
        if (isTerminal(run)) {
            return new Reconciliation(Action.ALREADY_TERMINAL, run.getLastErrorCode(), false, false,
                    "Registry 已是终态，不重复执行/写入");
        }
        if (lease == null) {
            // 任何会写入的路径都必须先取得执行权：杜绝绕过租约的写入分支
            throw new AgentRequestException(409, "AGENT_LEASE_REQUIRED",
                    "对账写入前必须先取得该 Run 的执行权（租约）");
        }
        if (checkpointState == null) {
            return new Reconciliation(Action.INITIAL_START, null, false, false, "无 Checkpoint，需首次初始化");
        }

        String runId = run.getRunId();
        String conversationId = checkpointState.conversationId() == null || checkpointState.conversationId().isBlank()
                ? run.getConversationId()
                : checkpointState.conversationId();
        String finishReason = checkpointState.finishReason();
        if (finishReason == null || finishReason.isBlank()) {
            // Graph 未收敛（有待执行调用或还在决策中）⇒ 交给 Resume，本类不重跑
            return new Reconciliation(Action.RESUME_GRAPH, null, false, false,
                    "Checkpoint 未到终态（pending=" + checkpointState.pendingToolCalls().size() + "）");
        }

        if (!AgentFinishReason.ANSWERED.name().equals(finishReason)) {
            // 不把兜底/未完成结果写成可信对话记忆（保持"无证据拒答"语义）
            String status = AgentFinishReason.ERROR.name().equals(finishReason) ? "FAILED" : "COMPLETED";
            boolean terminalWritten = registry.markStatus(lease, status, finishReason);
            log.info("[agent-reconcile] 未完成 Run 仅补终态: runId={}, finishReason={}, status={}, written={}",
                    runId, finishReason, status, terminalWritten);
            return new Reconciliation(Action.TERMINAL_INCOMPLETE, finishReason, false, terminalWritten,
                    terminalWritten ? "未完成结果不写入会话记忆，仅记录终态与原因"
                            : "执行权已易主，终态写入被拒（留待合法持有者补齐）");
        }

        // 窗口 A / B：ANSWERED ⇒ 恰好一次补交记忆（租约保护的令牌 + 同事务），随后补终态
        boolean written = memoryCommitter.commitTurnOnce(lease, conversationId, checkpointState.question(),
                checkpointState.finalAnswer());
        boolean terminalWritten = registry.markStatus(lease, "COMPLETED", null);
        log.info("[agent-reconcile] 终态对账完成: runId={}, memoryWrittenNow={}, terminalWritten={}", runId, written,
                terminalWritten);
        return new Reconciliation(written ? Action.COMPENSATE_MEMORY : Action.COMPLETE_TERMINAL_ONLY,
                finishReason, written, terminalWritten,
                terminalWritten ? (written ? "窗口A：补交记忆并补终态" : "窗口B：记忆已提交，仅补终态")
                        : "记忆已处理，但终态写入被拒（执行权已易主）");
    }

    private static boolean isTerminal(AgentRun run) {
        return "COMPLETED".equals(run.getStatus()) || "FAILED".equals(run.getStatus());
    }
}
