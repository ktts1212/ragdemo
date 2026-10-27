package com.example.ragdemo.workflow.config;

import javax.sql.DataSource;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.mapper.DocumentsMapper;
import com.example.ragdemo.mapper.IngestionTaskMapper;
import com.example.ragdemo.rag.ingest.IngestionStatusUpdater;
import com.example.ragdemo.workflow.ingestion.IngestionGraphFactory;
import com.example.ragdemo.workflow.ingestion.IngestionState;
import com.example.ragdemo.workflow.ingestion.IngestionStateSerializer;
import com.example.ragdemo.workflow.ingestion.IngestionWorkflowRunner;
import com.example.ragdemo.workflow.ingestion.node.ChunkNode;
import com.example.ragdemo.workflow.ingestion.node.EmbedBatchNode;
import com.example.ragdemo.workflow.ingestion.node.FinalizeNode;
import com.example.ragdemo.workflow.ingestion.node.ParseArtifactNode;
import com.example.ragdemo.workflow.ingestion.node.PersistNode;
import com.example.ragdemo.workflow.ingestion.node.PrepareNode;
import com.example.ragdemo.workflow.runtime.FailureClassifier;
import com.example.ragdemo.workflow.runtime.TaskLeaseService;
import com.example.ragdemo.workflow.runtime.WorkflowExecutionCoordinator;
import com.example.ragdemo.workflow.runtime.WorkflowRecoveryScheduler;
import com.example.ragdemo.workflow.runtime.WorkflowReconciliationService;

/**
 * 真实入库图装配（step4 §4/§7）。
 * <p>
 * <b>开关</b>：{@code rag.workflow.ingestion.enabled}（默认 <b>false</b>）——
 * 关闭时本配置整体不加载，业务启动<b>不会</b>额外连接/初始化 checkpoint 存储，旧链路行为不变。
 * <p>
 * Saver 复用 Step 2/3 已验证方案：{@code PostgresSaverV2} + Jackson 文本序列化 +
 * {@code releaseThread(false)}（崩溃后仍需读回 checkpoint 才能恢复）。
 * <b>严禁</b> {@code dropTablesFirst(true)}。
 */
@Configuration
@ConditionalOnProperty(name = "rag.workflow.ingestion.enabled", havingValue = "true")
public class IngestionGraphConfig {

    /**
     * 循环图的 recursionLimit：126 chunk / 32 ≈ 4 批 ⇒ 约 10 次节点执行；
     * 这里给足余量以支持更大的文档（默认 25 对大批量文档会偏紧）。
     */
    public static final int INGESTION_RECURSION_LIMIT = 200;

    @Bean
    public IngestionStateSerializer ingestionStateSerializer() {
        return new IngestionStateSerializer();
    }

    @Bean
    public PostgresSaverV2 ingestionPostgresSaver(DataSource dataSource,
            IngestionStateSerializer ingestionStateSerializer) throws Exception {
        return PostgresSaverV2.builder()
                .datasource(dataSource)
                // createTables(true)：jar 内 DDL 采用 IF NOT EXISTS，可重复执行且不破坏既有 checkpoint
                .createTables(true)
                .stateSerializer(ingestionStateSerializer)
                .build();
    }

    @Bean
    public IngestionGraphFactory ingestionGraphFactory(PrepareNode prepareNode,
            ParseArtifactNode parseArtifactNode, ChunkNode chunkNode, EmbedBatchNode embedBatchNode,
            PersistNode persistNode, FinalizeNode finalizeNode) {
        return new IngestionGraphFactory(prepareNode, parseArtifactNode, chunkNode, embedBatchNode,
                persistNode, finalizeNode);
    }

    @Bean
    public CompiledGraph<IngestionState> ingestionGraph(IngestionGraphFactory ingestionGraphFactory,
            PostgresSaverV2 ingestionPostgresSaver) throws GraphStateException {
        return ingestionGraphFactory.build()
                .compile(CompileConfig.builder()
                        .checkpointSaver(ingestionPostgresSaver)
                        .releaseThread(false)
                        .recursionLimit(INGESTION_RECURSION_LIMIT)
                        .build());
    }

    @Bean
    public IngestionWorkflowRunner ingestionWorkflowRunner(
            CompiledGraph<IngestionState> ingestionGraph) {
        return new IngestionWorkflowRunner(ingestionGraph);
    }

    // ==================== Step 5：租约 / 统一执行入口 / 三态对账 ====================
    // Coordinator 与 ReconciliationService 不标 @Service（否则开关关闭时会因缺少 Runner 而启动失败），
    // 统一在此受开关约束的配置里注册。

    @Bean
    public WorkflowExecutionCoordinator ingestionExecutionCoordinator(TaskLeaseService taskLeaseService,
            IngestionWorkflowRunner ingestionWorkflowRunner, DocumentsMapper documentsMapper,
            FailureClassifier failureClassifier, RagProperties ragProperties) {
        return new WorkflowExecutionCoordinator(taskLeaseService, ingestionWorkflowRunner,
                documentsMapper, failureClassifier, ragProperties);
    }

    @Bean
    public WorkflowReconciliationService ingestionReconciliationService(
            IngestionWorkflowRunner ingestionWorkflowRunner,
            WorkflowExecutionCoordinator ingestionExecutionCoordinator,
            IngestionTaskMapper ingestionTaskMapper,
            IngestionStatusUpdater ingestionStatusUpdater) {
        return new WorkflowReconciliationService(ingestionWorkflowRunner, ingestionExecutionCoordinator,
                ingestionTaskMapper, ingestionStatusUpdater);
    }

    /**
     * 自动恢复调度器：<b>默认关闭</b>，只有 {@code rag.workflow.ingestion.recovery-enabled=true} 才装配。
     * 开启它即可获得"服务重启后无需人工 resume 自动收敛"。
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnProperty(name = "rag.workflow.ingestion.recovery-enabled", havingValue = "true")
    public WorkflowRecoveryScheduler ingestionRecoveryScheduler(IngestionTaskMapper ingestionTaskMapper,
            DocumentsMapper documentsMapper, WorkflowReconciliationService ingestionReconciliationService,
            RagProperties ragProperties) {
        return new WorkflowRecoveryScheduler(ingestionTaskMapper, documentsMapper,
                ingestionReconciliationService, ragProperties);
    }
}
