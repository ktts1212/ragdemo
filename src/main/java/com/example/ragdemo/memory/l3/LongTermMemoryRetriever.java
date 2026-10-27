package com.example.ragdemo.memory.l3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.ragdemo.mapper.LongTermMemoryMapper;
import com.example.ragdemo.model.entity.MemorySearchHit;

import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;

/**
 * L3 长期记忆检索（Memory V1 · Phase 3）。
 *
 * <p><b>安全边界（全部在 SQL 层完成，调用方无法绕过）</b>：
 * <ul>
 *   <li>{@code principal_id} 只来自服务端配置（{@link L3PrincipalResolver}），不接受请求体/模型输入；</li>
 *   <li>只返回 {@code status='ACTIVE'} ⇒ CONFLICT / SUPERSEDED / REVOKED 永不进入正常上下文；</li>
 *   <li>作用域：精确 {@code scope_key} 匹配，或仅当 {@code includeGlobal=true} 时才附带全局记忆（{@code scope_key IS NULL}）；</li>
 *   <li>语义检索只使用 {@code embedding IS NOT NULL} 的行；embedding=NULL 的记忆只能被结构化条件召回。</li>
 * </ul>
 *
 * <p><b>失败语义</b>：结构化查询失败向上抛出（由调用方降级）；语义检索失败（embedding 服务不可用）
 * 只丢语义部分并保留结构化结果 —— 记忆检索退化，但不阻断回答。
 */
@Slf4j
@Service
public class LongTermMemoryRetriever {

    public static final int EMBEDDING_DIMENSION = 1024;

    /**
     * 检索条件。{@code text} 为空 ⇒ 只做结构化检索；{@code scopeKey=null} = 全局作用域。
     */
    public record Query(String text, String scopeKey, boolean includeGlobal, String canonicalKey,
            String subject, String predicate, int topK, Double minSimilarity) {

        public static Query semantic(String text, int topK) {
            return new Query(text, null, true, null, null, null, topK, null);
        }

        public static Query slot(String canonicalKey) {
            return new Query(null, null, true, canonicalKey, null, null, 1, null);
        }
    }

    /** 一条召回的长期记忆（含来源，可追溯）。 */
    public record Hit(Long memoryId, String memoryType, String subject, String predicate, String content,
            int version, float confidence, String canonicalKey, String sourceConversationId,
            String sourceMessageId, String sourceRunId, java.time.LocalDateTime createdAt, String matchKind,
            double score) {
    }

    private final LongTermMemoryMapper mapper;
    private final EmbeddingModel embeddingModel;
    private final L3PrincipalResolver principalResolver;
    private final MemoryProperties properties;

    public LongTermMemoryRetriever(LongTermMemoryMapper mapper, EmbeddingModel embeddingModel,
            L3PrincipalResolver principalResolver, MemoryProperties properties) {
        this.mapper = mapper;
        this.embeddingModel = embeddingModel;
        this.principalResolver = principalResolver;
        this.properties = properties;
    }

    /** L3 是否可读（开关 + 可信身份）。 */
    public boolean available() {
        return principalResolver.available();
    }

    /**
     * 检索当前服务端 principal 的记忆。返回按相关性排序（结构化命中优先，其后语义相似度降序）的 TopK。
     */
    public List<Hit> retrieve(Query query) {
        if (!available() || query == null) {
            return List.of();
        }
        String principalId = principalResolver.principal();
        int limit = clampTopK(query.topK());
        boolean structuredFilter = query.canonicalKey() != null || query.subject() != null
                || query.predicate() != null;
        boolean hasText = query.text() != null && !query.text().isBlank();

        // 互斥策略（避免把整表 ACTIVE 当成"相关结果"灌进上下文）：
        // - 给定结构化条件 ⇒ 只做结构化精确检索；
        // - 没有结构化条件且有文本 ⇒ 只做语义检索；
        // - 无文本也无条件 ⇒ 结构化（按作用域列出，供管理/诊断用）。
        List<MemorySearchHit> structured = !structuredFilter && hasText ? List.of()
                : mapper.searchStructured(principalId, query.scopeKey(), query.includeGlobal(),
                        query.canonicalKey(), query.subject(), query.predicate(), limit);
        List<MemorySearchHit> semantic = List.of();
        if (structuredFilter || !hasText) {
            semantic = List.of();
        } else {
            float[] vector = embed(query.text());
            if (vector != null) {
                try {
                    semantic = mapper.searchSemantic(principalId, query.scopeKey(), query.includeGlobal(),
                            LongTermMemoryStore.toVectorLiteral(vector),
                            query.minSimilarity() == null ? defaultMinSimilarity() : query.minSimilarity(), limit);
                } catch (RuntimeException e) {
                    log.warn("[l3-retrieve] 语义检索失败，仅返回结构化结果: {}", e.toString());
                }
            }
        }

        // 结构化过滤条件明确时结构化优先；否则按语义相关性优先，结构化作为补充
        List<MemorySearchHit> ordered = new ArrayList<>(limit);
        Set<Long> seen = new LinkedHashSet<>();
        if (structuredFilter) {
            addAll(ordered, seen, structured, limit);
            addAll(ordered, seen, semantic, limit);
        } else {
            addAll(ordered, seen, semantic, limit);
            addAll(ordered, seen, structured, limit);
        }
        return ordered.stream().map(LongTermMemoryRetriever::toHit).toList();
    }

    /** 同槽位存在 CONFLICT 记录（供上下文构建器标注不确定性）。 */
    public Set<String> conflictSlots(List<Hit> hits) {
        if (!available() || hits.isEmpty()) {
            return Set.of();
        }
        List<String> keys = hits.stream().map(Hit::canonicalKey).filter(k -> k != null && !k.isBlank())
                .distinct().toList();
        if (keys.isEmpty()) {
            return Set.of();
        }
        try {
            return new LinkedHashSet<>(mapper.selectConflictSlots(principalResolver.principal(), keys));
        } catch (RuntimeException e) {
            log.warn("[l3-retrieve] 冲突标记查询失败（忽略，不影响召回）: {}", e.toString());
            return Set.of();
        }
    }

    private float[] embed(String text) {
        try {
            var embedding = embeddingModel.embed(text).content();
            float[] vector = embedding == null ? null : embedding.vector();
            if (vector == null || vector.length != EMBEDDING_DIMENSION) {
                log.warn("[l3-retrieve] embedding 维度异常（期望 {}），跳过语义检索", EMBEDDING_DIMENSION);
                return null;
            }
            return vector;
        } catch (RuntimeException e) {
            log.warn("[l3-retrieve] embedding 调用失败，跳过语义检索: {}", e.toString());
            return null;
        }
    }

    private int clampTopK(int requested) {
        int configured = Math.max(1, properties.getL3().getRetrievalTopK());
        int value = requested > 0 ? requested : configured;
        return Math.max(1, Math.min(value, 20));
    }

    private double defaultMinSimilarity() {
        return properties.getL3().getRetrievalMinSimilarity();
    }

    private static void addAll(List<MemorySearchHit> target, Set<Long> seen, List<MemorySearchHit> rows, int limit) {
        for (MemorySearchHit row : rows) {
            if (target.size() >= limit) {
                return;
            }
            if (row.getMemoryId() != null && seen.add(row.getMemoryId())) {
                target.add(row);
            }
        }
    }

    private static Hit toHit(MemorySearchHit row) {
        return new Hit(row.getMemoryId(), row.getMemoryType(), row.getSubject(), row.getPredicate(),
                row.getContent(), row.getVersion() == null ? 1 : row.getVersion(),
                row.getConfidence() == null ? 0f : row.getConfidence(), row.getCanonicalKey(),
                row.getSourceConversationId(), row.getSourceMessageId(), row.getSourceRunId(), row.getCreatedAt(),
                row.getMatchKind(), row.getScore() == null ? 0d : row.getScore());
    }
}
