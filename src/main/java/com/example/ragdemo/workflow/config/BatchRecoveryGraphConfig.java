package com.example.ragdemo.workflow.config;

import javax.sql.DataSource;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.ragdemo.workflow.poc.batch.BatchRecoveryGraph;
import com.example.ragdemo.workflow.poc.batch.BatchRecoveryRunner;
import com.example.ragdemo.workflow.poc.batch.BatchRecoveryState;
import com.example.ragdemo.workflow.poc.batch.BatchRecoveryStateSerializer;
import com.example.ragdemo.workflow.poc.batch.BatchResultStore;

/**
 * Step 3 批处理 PoC 装配（与 Step 2 的 {@code RecoveryTestGraphConfig} 并列、互不影响）。
 * <p>
 * <b>复用 Step 2 已验证的结论</b>：
 * <ul>
 *   <li>同一个 {@code PostgresSaverV2} + 同一批 {@code LG4J*} 表；</li>
 *   <li>{@code JacksonStateSerializer} 文本族（匹配 {@code state_data TEXT} 列）；</li>
 *   <li>{@code releaseThread(false)}：循环中途崩溃后必须还能读回 Checkpoint；</li>
 *   <li>独立 threadId 命名空间 {@code poc-batch-}。</li>
 * </ul>
 * <b>与 Step 2 的差异</b>：本图状态类型不同，因此需要<b>另一个 saver 实例</b>
 * （各持自己的 StateSerializer），但建表交给 Step 2 已有的 {@code createTables(true)} 完成，
 * 这里用 {@code createTables(false)} 避免重复执行 DDL。
 * <p>
 * <b>崩溃注入</b>：Spring 装配永远是 {@code CrashPolicy.none()}——{@code Runtime.halt}
 * 只允许出现在独立子进程 {@code BatchCrashMain} 中，绝不能在应用/测试 JVM 里触发。
 */
@Configuration
@ConditionalOnProperty(name = "rag.workflow.poc.enabled", havingValue = "true")
public class BatchRecoveryGraphConfig {

    /**
     * 循环图的 recursionLimit：5 批 = prepare + 5×batch + finish = 7 次节点执行，
     * 留足余量（并显式设置，避免依赖默认值）。实际默认值与计数方式由测试实测记录。
     */
    public static final int BATCH_RECURSION_LIMIT = 100;

    @Bean
    public BatchRecoveryStateSerializer pocBatchRecoveryStateSerializer() {
        return new BatchRecoveryStateSerializer();
    }

    @Bean
    public BatchResultStore pocBatchResultStore(DataSource dataSource) throws Exception {
        BatchResultStore store = new BatchResultStore(dataSource);
        store.ensureSchema();   // 只建/使用测试专用表 poc_batch_result，不碰任何业务表
        return store;
    }

    @Bean
    public PostgresSaverV2 pocBatchRecoveryPostgresSaver(DataSource dataSource,
            BatchRecoveryStateSerializer pocBatchRecoveryStateSerializer) throws Exception {
        return PostgresSaverV2.builder()
                .datasource(dataSource)
                .createTables(false)   // LG4J* 表已存在（Step 2 建的）
                .stateSerializer(pocBatchRecoveryStateSerializer)
                .build();
    }

    @Bean
    public CompiledGraph<BatchRecoveryState> pocBatchRecoveryGraph(BatchResultStore pocBatchResultStore,
            PostgresSaverV2 pocBatchRecoveryPostgresSaver) throws GraphStateException {
        return new BatchRecoveryGraph(pocBatchResultStore).build()
                .compile(CompileConfig.builder()
                        .checkpointSaver(pocBatchRecoveryPostgresSaver)
                        .releaseThread(false)
                        .recursionLimit(BATCH_RECURSION_LIMIT)
                        .build());
    }

    @Bean
    public BatchRecoveryRunner batchRecoveryRunner(CompiledGraph<BatchRecoveryState> pocBatchRecoveryGraph) {
        return new BatchRecoveryRunner(pocBatchRecoveryGraph);
    }
}
