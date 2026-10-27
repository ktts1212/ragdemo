package com.example.ragdemo.memory.l3;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ragdemo.mapper.LongTermMemoryMapper;
import com.example.ragdemo.model.entity.LongTermMemory;

import lombok.extern.slf4j.Slf4j;

/**
 * 长期记忆合并（Memory V1 · Phase 2B）。**确定性规则、代码决定 SQL 动作**：
 * <ol>
 *   <li>槽位 = {@code canonical_key}（principal|scope|type|subject|predicate）——不同 predicate 天然分离 ✓；</li>
 *   <li>同槽同内容 ⇒ {@link Outcome#NO_OP}（不新增 ACTIVE）；</li>
 *   <li>同槽不同内容 + 模型给出 {@code relation=UPDATE} ⇒ 新版本 ACTIVE + 旧版本 SUPERSEDED（版本链完整）；</li>
 *   <li>同槽不同内容 + 无明确更新意图 ⇒ 新增 {@code CONFLICT} 记录，**不覆盖**旧 ACTIVE；</li>
 *   <li>同槽已有 REVOKED ⇒ {@link Outcome#REVOKED_SKIPPED}（**不自动重新激活**）；</li>
 *   <li>并发：{@code SELECT … FOR UPDATE} 锁当前槽位 + 数据库部分唯一索引兜底（同槽最多一条 ACTIVE）。</li>
 * </ol>
 * 模型只提供素材与关系提示，**不能**指定 principal、作用域或直接决定 SQL。
 */
@Slf4j
@Service
public class MemoryConsolidator {

    public enum Outcome {
        INSERTED, NO_OP, SUPERSEDED, CONFLICT, REVOKED_SKIPPED
    }

    /** 跨槽更新要求的主语最小长度（过短=主题模糊，无法确认同槽 ⇒ 保守不替换）。 */
    private static final int MIN_CROSS_SLOT_SUBJECT_LENGTH = 4;

    private final LongTermMemoryMapper mapper;

    public MemoryConsolidator(LongTermMemoryMapper mapper, MemoryProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * 写入一条候选（**必须在短事务内调用**；本方法自身也是事务边界，便于单独使用）。
     *
     * @param embedding 已算好的 1024 维向量；调用方必须在事务外计算（策略 A：失败即重试，绝不写 NULL 完成态）
     */
    @Transactional
    public Outcome apply(String principalId, String scopeKey, MemoryCandidate candidate, float[] embedding,
            String conversationId, String messageId, String runId) {
        if (embedding == null || embedding.length != 1024) {
            throw new IllegalArgumentException("embedding 必须是 1024 维（调用方需在事务外算好）");
        }
        String slot = LongTermMemoryStore.canonicalKey(principalId, scopeKey, candidate);
        String hash = LongTermMemoryStore.contentHash(candidate.content());
        String literal = LongTermMemoryStore.toVectorLiteral(embedding);

        if (mapper.countRevoked(principalId, scopeKey, slot) > 0) {
            log.info("[l3-consolidate] 槽位已撤销，拒绝重新激活: slot={}", slot.substring(0, 12));
            return Outcome.REVOKED_SKIPPED;
        }

        LongTermMemory active = mapper.selectActiveForUpdate(principalId, scopeKey, slot);
        if (active != null && hash.equals(active.getContentHash())) {
            return Outcome.NO_OP;
        }
        int nextVersion = mapper.maxVersion(principalId, scopeKey, slot) + 1; // 版本单调递增

        if (active == null) {
            long newId = insert(principalId, scopeKey, candidate, slot, literal, "ACTIVE", nextVersion,
                    conversationId, messageId, runId);
            // 跨槽 UPDATE（模型本轮换了措辞 ⇒ canonical_key 不同）：
            // **禁止**只凭"同类型恰好一条 ACTIVE"推断同一槽位（Phase 4A 审计发现该规则会误覆盖
            // 不同主题的决策，例如"数据库选 PostgreSQL"被"向量模型改 e5-large"顶掉）。
            // 必须同时满足：① 谓词一致；② 主语字符二元组重叠 ≥ 阈值；③ 正文向量相似度 ≥ 阈值（SQL 层）。
            // 仍要求"同类型唯一 ACTIVE"；任何一项不满足 ⇒ 保守新增，不替换。
            if ("UPDATE".equals(candidate.relation())) {
                Long target = findCrossSlotTarget(principalId, scopeKey, candidate, literal, newId);
                if (target != null) {
                    mapper.supersede(target, newId);
                    log.info("[l3-consolidate] 跨槽更新（已通过谓词/主语/向量三重校验）: 旧={} -> 新={}", target,
                            newId);
                    return Outcome.SUPERSEDED;
                }
                log.info("[l3-consolidate] 跨槽 UPDATE 未通过同槽校验 ⇒ 保守新增，不替换旧记录");
            }
            return Outcome.INSERTED;
        }

        if ("UPDATE".equals(candidate.relation())) {
            // 三阶段（同一事务，保证原子）：
            // ① 新版本先以 CONFLICT 占位插入（此时不违反"同槽最多一条 ACTIVE"）；
            // ② supersede 旧 ACTIVE 并把 superseded_by 指向新行（满足外键）；
            // ③ 把新行置为 ACTIVE。
            // 任何时刻都不会出现两条 ACTIVE；旧版本的来源与版本链完整保留。
            Long newId = mapper.nextMemoryId();
            insert(principalId, scopeKey, candidate, slot, literal, "CONFLICT", nextVersion, conversationId,
                    messageId, runId, newId);
            mapper.supersede(active.getMemoryId(), newId);
            mapper.activate(newId);
            log.info("[l3-consolidate] 版本更新: slot={}, {} -> {} (v{} -> v{})", slot.substring(0, 12),
                    active.getMemoryId(), newId, active.getVersion(), nextVersion);
            return Outcome.SUPERSEDED;
        }

        insert(principalId, scopeKey, candidate, slot, literal, "CONFLICT", nextVersion, conversationId, messageId,
                runId);
        log.info("[l3-consolidate] 冲突保留（不覆盖 ACTIVE）: slot={}, activeId={}", slot.substring(0, 12),
                active.getMemoryId());
        return Outcome.CONFLICT;
    }

    // ==================== Phase 4A：跨槽更新的"同槽校验" ====================

    private final MemoryProperties properties;

    /**
     * 找出可被本次 UPDATE 替换的**跨槽**旧记录；不满足任一条件即返回 null（保守不替换）。
     * <p>条件：同 principal/scope/type + 其他 ACTIVE + 正文相似度≥阈值（SQL） + 谓词一致 + 主语重叠≥阈值，
     * 且最终**恰好一条**匹配。
     */
    private Long findCrossSlotTarget(String principalId, String scopeKey, MemoryCandidate candidate,
            String vectorLiteral, long newId) {
        List<com.example.ragdemo.model.entity.MemorySearchHit> rows = mapper.searchUpdateCandidates(principalId,
                scopeKey, candidate.type(), vectorLiteral, properties.getL3().getCrossSlotMinContentSimilarity(),
                newId);
        String newPredicate = normalizeSlotPart(candidate.predicate());
        // 主语过短（<4 汉字/字符）无法确认槽位 ⇒ 一律不替换（"方案"/"另一个方案"这类模糊表达）
        if (normalizeSlotPart(candidate.subject()).length() < MIN_CROSS_SLOT_SUBJECT_LENGTH) {
            return null;
        }
        double highSim = properties.getL3().getCrossSlotHighConfidenceSimilarity();
        double minOverlap = properties.getL3().getCrossSlotMinSubjectOverlap();
        List<Long> matched = rows.stream()
                .filter(row -> Objects.equals(normalizeSlotPart(row.getPredicate()), newPredicate))
                .filter(row -> normalizeSlotPart(row.getSubject()).length() >= MIN_CROSS_SLOT_SUBJECT_LENGTH)
                .filter(row -> (row.getScore() != null && row.getScore() >= highSim)
                        || subjectOverlap(row.getSubject(), candidate.subject()) >= minOverlap)
                .map(com.example.ragdemo.model.entity.MemorySearchHit::getMemoryId)
                .toList();
        log.debug("[l3-consolidate] 跨槽候选: new(subject={}, predicate={}) rows={} matched={}", candidate.subject(),
                candidate.predicate(),
                rows.stream().map(r -> r.getMemoryId() + ":" + r.getSubject() + ":" + r.getPredicate() + ":sim="
                        + String.format("%.3f", r.getScore()) + ":overlap="
                        + String.format("%.2f", subjectOverlap(r.getSubject(), candidate.subject()))).toList(),
                matched);
        return matched.size() == 1 ? matched.get(0) : null;
    }

    static String normalizeSlotPart(String value) {        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]", "");
    }

    /** 主语字符二元组 Jaccard（"项目向量模型" vs "向量模型" = 3/5 = 0.6；不相关主题 = 0）。 */
    static double subjectOverlap(String left, String right) {
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return intersection.size() / (double) union.size();
    }

    private static Set<String> bigrams(String value) {
        String normalized = normalizeSlotPart(value);
        Set<String> grams = new HashSet<>();
        if (normalized.length() == 1) {
            grams.add(normalized);
        }
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    private long insert(String principalId, String scopeKey, MemoryCandidate candidate, String slot, String literal,
            String status, int version, String conversationId, String messageId, String runId) {        return insert(principalId, scopeKey, candidate, slot, literal, status, version, conversationId, messageId,
                runId, null);
    }

    private long insert(String principalId, String scopeKey, MemoryCandidate candidate, String slot, String literal,
            String status, int version, String conversationId, String messageId, String runId, Long presetId) {
        Long id = presetId != null ? presetId : mapper.nextMemoryId();
        LongTermMemory row = LongTermMemoryStore.newRow(principalId, scopeKey, candidate, slot, version, status,
                literal, conversationId, messageId, runId);
        row.setMemoryId(id);
        row.setEmbeddingLiteral(literal);
        mapper.insert(row);
        return id;
    }
}
