package com.example.ragdemo.workflow.testkit;

import java.util.Map;

/**
 * 故障注入契约（step5 §3）：<b>测试 Profile 专用</b>。
 * <p>
 * 生产实现永远是 {@code NoOpWorkflowFaultInjector}（什么都不做）；只有
 * {@code rag.workflow.testkit.enabled=true} 时才由测试替身接管并在指定故障点
 * {@code Runtime.halt(137)}。<b>禁止</b>通过任何外部请求参数触发 halt。
 * <p>
 * Context 只放标识（taskId/documentId/batchIndex 等），<b>不得</b>放论文正文或凭据。
 */
@FunctionalInterface
public interface WorkflowFaultInjector {

    void at(String point, Map<String, Object> context);

    /** 故障点常量：与节点里"外部副作用已完成但节点尚未返回"的窗口一一对应。 */
    final class Points {

        /** A1：canonical / marker-raw / provenance sidecar 已写，节点未返回（Checkpoint 未提交） */
        public static final String PARSE_AFTER_ARTIFACTS = "parse.afterArtifacts";
        /** A2：第 N 批向量产物已写，节点未返回 */
        public static final String EMBED_AFTER_BATCH_ARTIFACT = "embed.afterBatchArtifact";
        /** A4：persist 事务提交【之前】 */
        public static final String PERSIST_BEFORE_COMMIT = "persist.beforeCommit";
        /** A3：persist 事务已提交（documents=indexed）但节点未返回 */
        public static final String PERSIST_AFTER_COMMIT = "persist.afterCommit";
        /** A5：task 已标 success 但 Graph 尚未 END */
        public static final String FINALIZE_AFTER_TASK_SUCCESS = "finalize.afterTaskSuccess";

        private Points() {
        }
    }
}
