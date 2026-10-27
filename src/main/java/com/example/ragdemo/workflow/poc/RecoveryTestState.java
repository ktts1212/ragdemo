package com.example.ragdemo.workflow.poc;

import java.util.List;
import java.util.Map;

import org.bsc.langgraph4j.state.AgentState;

/**
 * Step 2 PoC 专用状态（与业务代码完全隔离）。
 * <p>
 * 只做持久化能力验证，不承载任何业务数据。
 * 字段：taskId / currentStep / processedCount / executionLog。
 * <p>
 * 必须可序列化（LangGraph4j 会把 state 写入 {@code LG4JCheckpoint.state_data}）。
 */
public class RecoveryTestState extends AgentState {

    public static final String TASK_ID = "taskId";
    public static final String CURRENT_STEP = "currentStep";
    public static final String PROCESSED_COUNT = "processedCount";
    public static final String EXECUTION_LOG = "executionLog";

    public RecoveryTestState(Map<String, Object> initData) {
        super(initData);
    }

    public String taskId() {
        return this.<String>value(TASK_ID).orElse("");
    }

    public String currentStep() {
        return this.<String>value(CURRENT_STEP).orElse("");
    }

    public int processedCount() {
        return this.<Integer>value(PROCESSED_COUNT, 0);
    }

    public List<String> executionLog() {
        return this.<List<String>>value(EXECUTION_LOG).orElse(List.of());
    }
}
