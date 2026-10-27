package com.example.ragdemo.agent.model;

/** Run 结束原因（稳定枚举，前端与测试据以判断）。 */
public enum AgentFinishReason {

    /** 模型给出了带依据的最终回答 */
    ANSWERED,

    /** 预算（工具调用/LLM 轮次/上下文）用尽，基于已有证据作答并说明不确定性 */
    BUDGET_EXHAUSTED,

    /** 检测到重复工具调用循环，提前收敛 */
    LOOP_DETECTED,

    /** 工具执行失败且无法继续（仍会尝试基于已有证据作答） */
    TOOL_FAILURE,

    /** 内部错误（已收敛为安全文案） */
    ERROR,

    /** Agent 能力未启用 */
    DISABLED
}
