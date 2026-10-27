package com.example.ragdemo.memory.l3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.example.ragdemo.mapper.LongTermMemoryMapper;
import com.example.ragdemo.model.entity.LongTermMemory;

import lombok.extern.slf4j.Slf4j;

/**
 * 记忆槽位标识与内容哈希（Memory V1 · Phase 2B）。
 * <p><b>槽位身份</b>（{@code canonical_key}）= sha256(principal | scope | type | subject | predicate)
 * —— 同一 subject 的不同属性（predicate）属于**不同槽位**，不会被误合并。
 * <p><b>内容版本</b>（{@code content_hash}）= sha256(归一化后的 content) —— 判断"同槽同内容"。
 * <p>来源（conversation / message / run）单独作为列保存，不参与槽位身份。
 */
@Slf4j
@Service
public class LongTermMemoryStore {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final LongTermMemoryMapper mapper;

    public LongTermMemoryStore(LongTermMemoryMapper mapper) {
        this.mapper = mapper;
    }

    /** 槽位身份（不含内容）。 */
    public static String canonicalKey(String principalId, String scopeKey, MemoryCandidate candidate) {
        return sha256(String.join("|",
                principalId,
                scopeKey == null ? "" : scopeKey,
                candidate.type(),
                normalize(candidate.subject()),
                candidate.predicate() == null ? "" : normalize(candidate.predicate())));
    }

    /** 内容版本哈希。 */
    public static String contentHash(String content) {
        return sha256(normalize(content));
    }

    static String normalize(String text) {
        return text == null ? "" : WHITESPACE.matcher(text.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 失败", e);
        }
    }

    /** 把 float[1024] 转成 pgvector 文本（参数化 SQL 里 CAST(? AS vector)）。 */
    public static String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    public int insert(LongTermMemory memory) {
        return mapper.insert(memory);
    }

    public static LongTermMemory newRow(String principalId, String scopeKey, MemoryCandidate candidate,
            String canonicalKey, int version, String status, String embeddingLiteral,
            String conversationId, String messageId, String runId) {
        LongTermMemory row = new LongTermMemory();
        row.setPrincipalId(principalId);
        row.setScopeKey(scopeKey);
        row.setMemoryType(candidate.type());
        row.setSubject(candidate.subject());
        row.setPredicate(candidate.predicate());
        row.setCanonicalKey(canonicalKey);
        row.setContent(candidate.content());
        row.setContentHash(contentHash(candidate.content()));
        row.setStatus(status);
        row.setVersion(version);
        row.setConfidence((float) candidate.confidence());
        row.setEmbeddingStatus(embeddingLiteral == null ? "PENDING" : "READY");
        row.setSourceConversationId(conversationId);
        row.setSourceMessageId(messageId);
        row.setSourceRunId(runId);
        // embedding 由调用方以参数形式单独绑定（见 Consolidator）
        return row;
    }
}
