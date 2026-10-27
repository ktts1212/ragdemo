package com.example.ragdemo.agent.runtime;

import com.example.ragdemo.config.AgentProperties;

/**
 * Run 级硬预算（从 {@link AgentProperties} 冻结而来）。
 * <p>
 * 一旦构造完成即不可变 ⇒ 模型无法通过任何工具参数提高上限（Step 3 §5）。
 */
public record AgentLimits(int maxToolCalls, int maxLlmTurns, int maxContextChars, int maxToolResultChars,
        int maxOutputChars, int maxDuplicateCalls) {

    public static AgentLimits from(AgentProperties properties) {
        return new AgentLimits(properties.getMaxToolCalls(), properties.getMaxLlmTurns(),
                properties.getMaxContextChars(), properties.getMaxToolResultChars(),
                properties.getMaxOutputChars(), properties.getMaxDuplicateCalls());
    }
}
