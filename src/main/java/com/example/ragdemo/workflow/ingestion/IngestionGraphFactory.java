package com.example.ragdemo.workflow.ingestion;

import java.util.HashMap;
import java.util.Map;

import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import com.example.ragdemo.workflow.ingestion.node.ChunkNode;
import com.example.ragdemo.workflow.ingestion.node.EmbedBatchNode;
import com.example.ragdemo.workflow.ingestion.node.FinalizeNode;
import com.example.ragdemo.workflow.ingestion.node.ParseArtifactNode;
import com.example.ragdemo.workflow.ingestion.node.PersistNode;
import com.example.ragdemo.workflow.ingestion.node.PrepareNode;

/**
 * 真实 ACADEMIC 入库图（step4 §4）。
 * <pre>
 * START → prepare → parseArtifact → chunk → embedBatch ⇄(还有批次) → persist → finalize → END
 * </pre>
 * <b>Channel 一律 {@code base}（last-write）</b>：没有任何 appender
 * ⇒ 节点重放时状态是"覆盖"而非"追加"，不会因重复执行累积脏数据（step4 §5）。
 * <p>
 * Checkpoint 边界 = <b>每个节点执行之后</b>各一条；因此"节点内崩溃"会让该节点至少一次重跑，
 * 而节点内所有外部副作用（artifact 落盘、批次向量、最终事务）都被设计成可安全重复。
 */
public class IngestionGraphFactory {

    public static final String NODE_PREPARE = "prepare";
    public static final String NODE_PARSE_ARTIFACT = "parseArtifact";
    public static final String NODE_CHUNK = "chunk";
    public static final String NODE_EMBED_BATCH = "embedBatch";
    public static final String NODE_PERSIST = "persist";
    public static final String NODE_FINALIZE = "finalize";

    private final PrepareNode prepareNode;
    private final ParseArtifactNode parseArtifactNode;
    private final ChunkNode chunkNode;
    private final EmbedBatchNode embedBatchNode;
    private final PersistNode persistNode;
    private final FinalizeNode finalizeNode;

    public IngestionGraphFactory(PrepareNode prepareNode, ParseArtifactNode parseArtifactNode,
            ChunkNode chunkNode, EmbedBatchNode embedBatchNode, PersistNode persistNode,
            FinalizeNode finalizeNode) {
        this.prepareNode = prepareNode;
        this.parseArtifactNode = parseArtifactNode;
        this.chunkNode = chunkNode;
        this.embedBatchNode = embedBatchNode;
        this.persistNode = persistNode;
        this.finalizeNode = finalizeNode;
    }

    /** state schema：全部 last-write，更新时返回绝对值。 */
    public static Map<String, Channel<?>> schema() {
        Map<String, Channel<?>> schema = new HashMap<>();
        // identity
        schema.put(IngestionState.TASK_ID, Channels.<Long>base(() -> 0L));
        schema.put(IngestionState.DB_DOCUMENT_ID, Channels.<Long>base(() -> 0L));
        schema.put(IngestionState.KNOWLEDGE_BASE_ID, Channels.<Long>base(() -> 0L));
        schema.put(IngestionState.DOCUMENT_VERSION, Channels.<Integer>base(() -> 1));
        schema.put(IngestionState.PARSE_MODE, Channels.<String>base(() -> "ACADEMIC"));
        schema.put(IngestionState.CHECKSUM, Channels.<String>base(() -> ""));
        schema.put(IngestionState.SOURCE_KEY, Channels.<String>base(() -> ""));
        schema.put(IngestionState.FILE_NAME, Channels.<String>base(() -> ""));
        schema.put(IngestionState.WORKFLOW_VERSION, Channels.<Integer>base(() -> 0));
        // artifact refs
        schema.put(IngestionState.MARKER_RAW_KEY, Channels.<String>base(() -> ""));
        schema.put(IngestionState.CANONICAL_KEY, Channels.<String>base(() -> ""));
        schema.put(IngestionState.CHUNKS_KEY, Channels.<String>base(() -> ""));
        schema.put(IngestionState.CHUNK_COUNT, Channels.<Integer>base(() -> 0));
        schema.put(IngestionState.CHUNKS_DIGEST, Channels.<String>base(() -> ""));
        schema.put(IngestionState.CHUNKER_VERSION, Channels.<String>base(() -> ""));
        // embedding
        schema.put(IngestionState.EMBEDDING_MODEL_ID, Channels.<String>base(() -> ""));
        schema.put(IngestionState.EMBEDDING_MODEL_VERSION, Channels.<String>base(() -> ""));
        schema.put(IngestionState.EMBEDDING_DIMENSION, Channels.<Integer>base(() -> 0));
        schema.put(IngestionState.EMBEDDING_BATCH_SIZE, Channels.<Integer>base(() -> 0));
        schema.put(IngestionState.EMBEDDING_FINGERPRINT, Channels.<String>base(() -> ""));
        schema.put(IngestionState.TOTAL_BATCHES, Channels.<Integer>base(() -> 0));
        schema.put(IngestionState.NEXT_BATCH_INDEX, Channels.<Integer>base(() -> 0));
        schema.put(IngestionState.EMBEDDING_ARTIFACT_PREFIX, Channels.<String>base(() -> ""));
        // completion
        schema.put(IngestionState.PERSISTED, Channels.<Boolean>base(() -> false));
        schema.put(IngestionState.STATUS, Channels.<String>base(() -> "NEW"));
        schema.put(IngestionState.ERROR_CODE, Channels.<String>base(() -> ""));
        schema.put(IngestionState.ERROR_MESSAGE, Channels.<String>base(() -> ""));
        // Step 5：执行权（由 Coordinator 注入；不参与序列化大对象）
        schema.put(IngestionState.FENCING_TOKEN, Channels.<Long>base(() -> 0L));
        schema.put(IngestionState.WORKER_ID, Channels.<String>base(() -> ""));
        return schema;
    }

    public StateGraph<IngestionState> build() throws GraphStateException {
        StateGraph<IngestionState> graph =
                new StateGraph<>(schema(), new IngestionStateSerializer());

        graph.addNode(NODE_PREPARE, AsyncNodeAction.node_async(prepareNode::apply));
        graph.addNode(NODE_PARSE_ARTIFACT, AsyncNodeAction.node_async(parseArtifactNode::apply));
        graph.addNode(NODE_CHUNK, AsyncNodeAction.node_async(chunkNode::apply));
        graph.addNode(NODE_EMBED_BATCH, AsyncNodeAction.node_async(embedBatchNode::apply));
        graph.addNode(NODE_PERSIST, AsyncNodeAction.node_async(persistNode::apply));
        graph.addNode(NODE_FINALIZE, AsyncNodeAction.node_async(finalizeNode::apply));

        graph.addEdge(StateGraph.START, NODE_PREPARE);
        graph.addEdge(NODE_PREPARE, NODE_PARSE_ARTIFACT);
        graph.addEdge(NODE_PARSE_ARTIFACT, NODE_CHUNK);
        graph.addEdge(NODE_CHUNK, NODE_EMBED_BATCH);
        graph.addConditionalEdges(NODE_EMBED_BATCH, AsyncEdgeAction.edge_async(this::routeAfterBatch),
                Map.of(NODE_EMBED_BATCH, NODE_EMBED_BATCH, NODE_PERSIST, NODE_PERSIST));
        graph.addEdge(NODE_PERSIST, NODE_FINALIZE);
        graph.addEdge(NODE_FINALIZE, StateGraph.END);
        return graph;
    }

    /** 条件边：批次跑完才进 persist，否则继续 embedBatch。 */
    private String routeAfterBatch(IngestionState state) {
        return state.nextBatchIndex() >= state.totalBatches() ? NODE_PERSIST : NODE_EMBED_BATCH;
    }
}
