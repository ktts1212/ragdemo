package com.example.ragdemo.workflow.config;

import javax.sql.DataSource;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.ragdemo.workflow.poc.RecoveryTestGraph;
import com.example.ragdemo.workflow.poc.RecoveryTestRunner;
import com.example.ragdemo.workflow.poc.RecoveryTestState;
import com.example.ragdemo.workflow.poc.RecoveryTestStateSerializer;

/**
 * Step 2 PoC 装配（与业务装配完全隔离）。
 * <p>
 * <b>PostgresSaverV2 初始化方式</b>（均为 1.9.4 真实 API）：
 * <pre>
 * PostgresSaverV2.builder()
 *     .datasource(springBootDataSource)   // ★复用 Spring Boot DataSource
 *     .createTables(true)                 // 建 LG4J* 表（IF NOT EXISTS）
 *     .stateSerializer(new RecoveryTestStateSerializer())  // JSON 文本，匹配 v2.1 的 TEXT 列
 *     .build();                           // throws Exception
 * </pre>
 * <b>安全约束</b>：<b>绝不</b>使用 {@code dropTablesFirst(true)}；只会创建/写入
 * LangGraph4j 自带的 {@code LG4JThread / LG4JThreadTag / LG4JCheckpoint} 三张表，
 * 不触碰任何 RAG 业务表。
 * <p>
 * <b>{@code releaseThread(false)} —— 实测结论（含踩坑记录）</b>：
 * <ul>
 *   <li>{@code true}：图跑完后 saver 会 ① 往 {@code LG4JThreadTag} 写一行
 *       {@code is_released=true, released_version=1}，② 执行
 *       <b>{@code DELETE FROM LG4JThread}</b>（见 jar 内 {@code db/v2.1__commands.sql}
 *       的 {@code sqlReleaseThread_deleteThread}）。而 V2 的 {@code sqlSelectCheckpoints}
 *       是 {@code JOIN LG4JThread} 按 {@code thread_name} 定位 checkpoint，
 *       因此 thread 行一被删，checkpoint 立即"不可达"，
 *       {@code CompiledGraph.getState} 抛 {@code IllegalStateException: Missing Checkpoint!}
 *       —— 但物理行仍留在 {@code LG4JCheckpoint} 表中（实测 32 行）。
 *       即 {@code releaseThread(true)} 是"<b>归档+解除可达</b>"语义，
 *       适用于"流程彻底结束、只留审计 tag"的场景。</li>
 *   <li>{@code false}：thread 行与 checkpoint 一直保留，可被任意<b>新的</b> Saver 实例读回
 *       —— 这正是崩溃恢复需要的语义。</li>
 * </ul>
 * 本 PoC 的目标是"崩溃后读回状态"，故此处取 {@code false}；
 * {@code true} 的语义由 {@code RecoveryTestGraphTest#releaseThreadSemanticsAreOpposite} 单独验证。
 */
@Configuration
@ConditionalOnProperty(name = "rag.workflow.poc.enabled", havingValue = "true")
public class RecoveryTestGraphConfig {

    @Bean
    public RecoveryTestStateSerializer pocRecoveryTestStateSerializer() {
        return new RecoveryTestStateSerializer();
    }

    @Bean
    public PostgresSaverV2 pocRecoveryPostgresSaver(DataSource dataSource,
            RecoveryTestStateSerializer serializer) throws Exception {
        return PostgresSaverV2.builder()
                .datasource(dataSource)
                .createTables(true)
                .stateSerializer(serializer)
                .build();
    }

    @Bean
    public CompiledGraph<RecoveryTestState> pocRecoveryTestGraph(PostgresSaverV2 pocRecoveryPostgresSaver)
            throws GraphStateException {
        return new RecoveryTestGraph().build()
                .compile(CompileConfig.builder()
                        .checkpointSaver(pocRecoveryPostgresSaver)
                        // false ⇒ 保留 thread 行与 checkpoint，getState 可读回（崩溃恢复前提）
                        .releaseThread(false)
                        .build());
    }

    /**
     * Runner 必须与图在同一开关下装配：Runner 已去掉 {@code @Component}，
     * 否则普通启动（未开启 PoC 开关）会因缺少 PoC Graph bean 而启动失败。
     */
    @Bean
    public RecoveryTestRunner pocRecoveryTestRunner(CompiledGraph<RecoveryTestState> pocRecoveryTestGraph) {
        return new RecoveryTestRunner(pocRecoveryTestGraph);
    }
}
