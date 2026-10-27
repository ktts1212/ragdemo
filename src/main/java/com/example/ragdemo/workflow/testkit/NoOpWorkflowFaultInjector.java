package com.example.ragdemo.workflow.testkit;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 生产/默认的故障注入实现：<b>恒为空操作</b>。
 * <p>
 * 与测试替身互斥：只有 {@code rag.workflow.testkit.enabled} 非 true（含缺省）时才装配本类，
 * 因此生产与普通测试都不可能因故障注入而中断 JVM。
 */
@Component
@ConditionalOnProperty(name = "rag.workflow.testkit.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpWorkflowFaultInjector implements WorkflowFaultInjector {

    @Override
    public void at(String point, Map<String, Object> context) {
        // 故意什么都不做：生产不可通过任何输入触发崩溃。
    }
}
