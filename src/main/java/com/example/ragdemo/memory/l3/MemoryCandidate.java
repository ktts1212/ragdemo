package com.example.ragdemo.memory.l3;

import java.util.Locale;
import java.util.Set;

/**
 * 一条候选长期记忆（Extractor 产出、Policy 审核、Consolidator 落库前的唯一数据形态）。
 * <p>构造期即做**严格校验**（类型/长度/置信度），非法候选不允许进入后续阶段。
 */
public record MemoryCandidate(String type, String subject, String predicate, String content, double confidence,
        String relation) {

    public static final Set<String> TYPES = Set.of("FACT", "PREFERENCE", "DECISION");
    public static final Set<String> RELATIONS = Set.of("UPDATE", "CONFLICT");
    private static final int MAX_SUBJECT = 128;
    private static final int MAX_PREDICATE = 128;

    /** 兼容旧调用（无 predicate/relation）。 */
    public MemoryCandidate(String type, String subject, String content, double confidence) {
        this(type, subject, null, content, confidence, null);
    }

    public MemoryCandidate {
        type = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        subject = subject == null ? "" : subject.trim();
        predicate = predicate == null || predicate.isBlank() ? null : predicate.trim();
        content = content == null ? "" : content.trim();
        relation = relation == null || relation.isBlank() ? null : relation.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("非法记忆类型: " + type);
        }
        if (subject.isBlank() || subject.length() > MAX_SUBJECT) {
            throw new IllegalArgumentException("subject 非法（空或超过 " + MAX_SUBJECT + " 字符）");
        }
        if (predicate != null && predicate.length() > MAX_PREDICATE) {
            throw new IllegalArgumentException("predicate 过长（上限 " + MAX_PREDICATE + " 字符）");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (confidence < 0d || confidence > 1d) {
            throw new IllegalArgumentException("confidence 必须位于 [0,1]");
        }
        if (relation != null && !RELATIONS.contains(relation)) {
            throw new IllegalArgumentException("非法 relation: " + relation + "（仅允许 UPDATE/CONFLICT）");
        }
    }
}
