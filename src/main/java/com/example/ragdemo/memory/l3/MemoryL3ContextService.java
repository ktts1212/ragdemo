package com.example.ragdemo.memory.l3;

import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * L3 上下文门面（Memory V1 · Phase 3）：Router → Retriever → ContextBuilder，供 Agent 与传统 RAG 共用。
 *
 * <p>返回的是**冻结快照**（文本 + 来源），调用方应把它固化进自己的状态（Agent 存 State，
 * 传统 RAG 只拼进本轮 Prompt），不要在后续步骤重新查询。
 *
 * <p><b>降级</b>：任何检索/向量/构建异常 ⇒ 返回空快照（{@code degraded=true}），
 * 回答继续使用 L1+L2，不因记忆失败而失败。
 */
@Slf4j
@Service
public class MemoryL3ContextService {

    /** 冻结快照。{@code context} 为空表示"本轮不注入 L3"。 */
    public record Snapshot(String context, List<MemoryContextBuilder.Source> sources, String sourcesJson, int items,
            boolean applied, boolean degraded, String reason) {

        public boolean hasContent() {
            return context != null && !context.isBlank();
        }

        static Snapshot empty(boolean degraded, String reason) {
            return new Snapshot("", List.of(), "[]", 0, false, degraded, reason);
        }
    }

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final MemoryRetrievalRouter router;
    private final LongTermMemoryRetriever retriever;
    private final MemoryContextBuilder builder;
    private final MemoryProperties properties;

    public MemoryL3ContextService(MemoryRetrievalRouter router, LongTermMemoryRetriever retriever,
            MemoryContextBuilder builder, MemoryProperties properties) {
        this.router = router;
        this.retriever = retriever;
        this.builder = builder;
        this.properties = properties;
    }

    /**
     * @param explicitRequest 用户/调用方明确要求使用长期记忆（跳过路由的"论文问题默认不查"规则）
     * @param scopeKey        作用域（null = V1 全局）
     */
    public Snapshot load(String question, boolean explicitRequest, String scopeKey) {
        if (!retriever.available()) {
            return Snapshot.empty(false, "L3_DISABLED");
        }
        MemoryRetrievalRouter.Decision decision = properties.getL3().isRouterEnabled()
                ? router.route(question, explicitRequest)
                : (explicitRequest ? new MemoryRetrievalRouter.Decision(true, "EXPLICIT_REQUEST")
                        : new MemoryRetrievalRouter.Decision(false, "ROUTER_DISABLED"));
        if (!decision.useMemory()) {
            return Snapshot.empty(false, decision.reason());
        }
        try {
            var hits = retriever.retrieve(new LongTermMemoryRetriever.Query(question, scopeKey, true, null, null,
                    null, properties.getL3().getRetrievalTopK(), null));
            if (hits.isEmpty()) {
                return Snapshot.empty(false, "NO_MATCH");
            }
            var built = builder.build(hits, retriever.conflictSlots(hits),
                    properties.getL3().getContextTokenBudget(), properties.getL3().getMaxInjectedItems());
            if (!built.hasContent()) {
                return Snapshot.empty(false, "EMPTY_AFTER_FILTER");
            }
            String sourcesJson = JSON.writeValueAsString(built.sources());
            log.info("[l3-context] 注入 L3: items={}, tokens≈{}, route={}", built.items(), built.estimatedTokens(),
                    decision.reason());
            return new Snapshot(built.text(), built.sources(), sourcesJson, built.items(), true, false,
                    decision.reason());
        } catch (RuntimeException e) {
            log.warn("[l3-context] L3 检索失败，降级为 L1+L2: {}", e.toString());
            return Snapshot.empty(true, "RETRIEVAL_FAILED");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[l3-context] 来源序列化失败，降级: {}", e.getMessage());
            return Snapshot.empty(true, "SOURCE_ENCODE_FAILED");
        }
    }
}
