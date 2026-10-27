package com.example.ragdemo.agent.model;

/** Agent 对话消息角色（仅本步内存态使用，Step 4 可原样持久化）。 */
public enum AgentMessageRole {

    /** 系统提示（不可信资料边界、输出要求等） */
    SYSTEM,

    /** 用户问题 */
    USER,

    /** 模型回复（可能携带 tool calls） */
    AI,

    /** 工具执行结果（不可信资料） */
    TOOL
}
