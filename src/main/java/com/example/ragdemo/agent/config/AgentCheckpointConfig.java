package com.example.ragdemo.agent.config;

import javax.sql.DataSource;

import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.ragdemo.agent.graph.AgentStateSerializer;

/**
 * Agent 图 Durable Checkpoint 装配（Step 4 §5）。
 * <p>
 * <b>开关</b>：{@code rag.agent.durable.enabled}（默认 <b>false</b>）—— 关闭时本配置整体不加载，
 * Agent 保持 Step 3 的单请求内存态行为（`rag.agent.enabled` 仍是总闸）。
 * <p>
 * 与入库图的关系：可复用同一个 DataSource，但
 * <ul>
 *   <li><b>Serializer 独立</b>（{@link AgentStateSerializer}，只服务 Agent 状态）；</li>
 *   <li><b>threadId 空间隔离</b>（所有 Agent threadId 以 {@code agent-} 前缀）；</li>
 *   <li>不使用 {@code dropTablesFirst(true)}，不触碰既有 IngestionGraph checkpoint 行。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "rag.agent.durable.enabled", havingValue = "true")
public class AgentCheckpointConfig {

    /** Agent 图的 recursionLimit（与内存态同口径：轮次 ×4 + 余量，真正的收敛保障在节点内预算）。 */
    public static final int AGENT_RECURSION_LIMIT = 40;

    @Bean
    public AgentStateSerializer agentStateSerializer() {
        return new AgentStateSerializer();
    }

    @Bean
    public PostgresSaverV2 agentPostgresSaver(DataSource dataSource, AgentStateSerializer agentStateSerializer)
            throws Exception {
        return PostgresSaverV2.builder()
                .datasource(dataSource)
                // createTables(true)：jar 内 DDL 为 IF NOT EXISTS，可重复执行且不破坏既有 checkpoint
                .createTables(true)
                .stateSerializer(agentStateSerializer)
                .build();
    }
}
