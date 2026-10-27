package com.example.ragdemo.agent.model;

/**
 * 每次工具调用的 trace（Step 3 §4）。
 * <p>
 * <b>禁止</b>记录 PDF handle、完整证据正文、API Key；{@code outcome} 只用稳定短码。
 *
 * @param stepIndex      本 Run 内序号
 * @param toolName       工具/方法名（callable 名）
 * @param requestId      该次 tool call 的 requestId
 * @param outcome        OK / ERROR:&lt;CODE&gt; / DUPLICATE_SKIPPED / REJECTED / BUDGET_EXCEEDED
 * @param elapsedMs      执行耗时
 * @param evidenceCount  本次产出的证据条数
 * @param truncated      结果是否被服务端裁剪
 */
public record AgentTraceEntry(int stepIndex, String toolName, String requestId, String outcome,
        long elapsedMs, int evidenceCount, boolean truncated) {
}
