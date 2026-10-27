package com.example.ragdemo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.example.ragdemo.model.entity.LongTermMemory;

/**
 * L3 长期记忆 Mapper（Memory V1 · Phase 2B）。
 * <p>槽位 = {@code (principal_id, scope_key, canonical_key)}；同一槽位**至多一条 ACTIVE** 由
 * V7 的部分唯一索引 {@code uk_ltm_active_slot} 在数据库层保证。
 */
@Mapper
public interface LongTermMemoryMapper {

    /** 预分配主键（避免依赖 generated keys 的驱动差异）。 */
    @Select("SELECT nextval('long_term_memory_seq')")
    Long nextMemoryId();

    @Insert("""
            INSERT INTO long_term_memories
                (memory_id, principal_id, scope_key, memory_type, subject, predicate, canonical_key,
                 content, content_hash, embedding, embedding_status, status, version, confidence,
                 source_conversation_id, source_message_id, source_run_id, created_at, updated_at)
            VALUES (#{memoryId}, #{principalId}, #{scopeKey}, #{memoryType}, #{subject},
                    #{predicate}, #{canonicalKey}, #{content}, #{contentHash},
                    CAST(#{embeddingLiteral} AS vector), #{embeddingStatus}, #{status}, #{version}, #{confidence},
                    #{sourceConversationId}, #{sourceMessageId}, #{sourceRunId}, NOW(), NOW())
            """)
    int insert(LongTermMemory memory);

    /** 当前槽位的 ACTIVE 行（并加行锁，供 Consolidator 在事务内串行化同槽更新）。 */
    @Select("""
            SELECT * FROM long_term_memories
             WHERE principal_id = #{principalId} AND COALESCE(scope_key, '') = COALESCE(#{scopeKey}, '')
               AND canonical_key = #{canonicalKey} AND status = 'ACTIVE'
             FOR UPDATE
            """)
    LongTermMemory selectActiveForUpdate(@Param("principalId") String principalId,
            @Param("scopeKey") String scopeKey, @Param("canonicalKey") String canonicalKey);

    /** 同槽是否已有 REVOKED 记忆（撤销后**不得自动重新激活**）。 */
    @Select("""
            SELECT count(*) FROM long_term_memories
             WHERE principal_id = #{principalId} AND COALESCE(scope_key, '') = COALESCE(#{scopeKey}, '')
               AND canonical_key = #{canonicalKey} AND status = 'REVOKED'
            """)
    long countRevoked(@Param("principalId") String principalId, @Param("scopeKey") String scopeKey,
            @Param("canonicalKey") String canonicalKey);

    @Select("""
            SELECT COALESCE(MAX(version), 0) FROM long_term_memories
             WHERE principal_id = #{principalId} AND COALESCE(scope_key, '') = COALESCE(#{scopeKey}, '')
               AND canonical_key = #{canonicalKey}
            """)
    int maxVersion(@Param("principalId") String principalId, @Param("scopeKey") String scopeKey,
            @Param("canonicalKey") String canonicalKey);

    /** 旧版本标记 SUPERSEDED，并记录被谁取代（版本链可追溯）。 */
    @Update("""
            UPDATE long_term_memories
               SET status = 'SUPERSEDED', superseded_by = #{supersededBy}, updated_at = NOW()
             WHERE memory_id = #{memoryId} AND status = 'ACTIVE'
            """)
    int supersede(@Param("memoryId") long memoryId, @Param("supersededBy") long supersededBy);

    /** 内部管理：撤销一条记忆（REVOKED 不会被自动重新激活）。 */
    @Update("""
            UPDATE long_term_memories SET status = 'REVOKED', updated_at = NOW()
             WHERE memory_id = #{memoryId} AND status IN ('ACTIVE', 'CONFLICT')
            """)
    int markRevoked(@Param("memoryId") long memoryId);

    /**
     * 把"待激活"的新版本置为 ACTIVE（配合 supersede 的两步顺序：先插 CONFLICT 占位 → supersede 旧行 →
     * 再激活），从而同时满足 superseded_by 外键与"同槽最多一条 ACTIVE"的部分唯一索引。
     */
    @Update("""
            UPDATE long_term_memories SET status = 'ACTIVE', updated_at = NOW()
             WHERE memory_id = #{memoryId} AND status = 'CONFLICT'
            """)
    int activate(@Param("memoryId") long memoryId);

    /** 版本历史（按 version 升序）。 */
    @Select("""
            SELECT * FROM long_term_memories
             WHERE principal_id = #{principalId} AND COALESCE(scope_key, '') = COALESCE(#{scopeKey}, '')
               AND canonical_key = #{canonicalKey}
             ORDER BY version ASC
            """)
    List<LongTermMemory> versionHistory(@Param("principalId") String principalId,
            @Param("scopeKey") String scopeKey, @Param("canonicalKey") String canonicalKey);

    /** 同槽同内容的既有行（含非 ACTIVE，用于去重判断）。 */
    @Select("""
            SELECT count(*) FROM long_term_memories
             WHERE principal_id = #{principalId} AND COALESCE(scope_key, '') = COALESCE(#{scopeKey}, '')
               AND canonical_key = #{canonicalKey} AND content_hash = #{contentHash}
            """)
    long countBySlotAndContent(@Param("principalId") String principalId, @Param("scopeKey") String scopeKey,
            @Param("canonicalKey") String canonicalKey, @Param("contentHash") String contentHash);

    @Select("SELECT * FROM long_term_memories WHERE memory_id = #{memoryId}")
    LongTermMemory selectById(@Param("memoryId") long memoryId);

    @Select("""
            SELECT count(*) FROM long_term_memories
             WHERE principal_id = #{principalId} AND canonical_key = #{canonicalKey} AND status = 'ACTIVE'
            """)
    long countActive(@Param("principalId") String principalId, @Param("canonicalKey") String canonicalKey);

    /** 待补全向量的行数（策略 A 下应为 0；保留用于观测）。 */
    @Select("SELECT count(*) FROM long_term_memories WHERE embedding_status = 'PENDING'")
    long countEmbeddingPending();

    // ==================== Phase 3：检索（身份/状态/作用域过滤全部在 SQL 层） ====================

    /**
     * 结构化检索：principal + ACTIVE + 作用域；支持 canonical_key / subject / predicate 精确匹配。
     * <p>不要求 embedding（embedding=NULL 的记忆也能通过结构化条件召回）。
     */
    @Select("""
            <script>
            SELECT memory_id, memory_type, subject, predicate, content, version, confidence, canonical_key,
                   source_conversation_id, source_message_id, source_run_id, created_at,
                   1.0 AS score, 'STRUCTURED' AS match_kind
              FROM long_term_memories
             WHERE principal_id = #{principalId}
               AND status = 'ACTIVE'
            <choose>
              <when test="scopeKey == null">AND scope_key IS NULL</when>
              <otherwise>AND (scope_key = #{scopeKey} OR (#{includeGlobal} AND scope_key IS NULL))</otherwise>
            </choose>
            <if test="canonicalKey != null">AND canonical_key = #{canonicalKey}</if>
            <if test="subject != null">AND subject = #{subject}</if>
            <if test="predicate != null">AND predicate = #{predicate}</if>
             ORDER BY updated_at DESC, memory_id DESC
             LIMIT #{limit}
            </script>
            """)
    List<com.example.ragdemo.model.entity.MemorySearchHit> searchStructured(
            @Param("principalId") String principalId, @Param("scopeKey") String scopeKey,
            @Param("includeGlobal") boolean includeGlobal, @Param("canonicalKey") String canonicalKey,
            @Param("subject") String subject, @Param("predicate") String predicate,
            @Param("limit") int limit);

    /**
     * 语义检索：principal + ACTIVE + embedding 非空 + 作用域；按 cosine 距离升序，相似度阈值在 SQL 层过滤。
     */
    @Select("""
            <script>
            SELECT memory_id, memory_type, subject, predicate, content, version, confidence, canonical_key,
                   source_conversation_id, source_message_id, source_run_id, created_at,
                   (1 - (embedding &lt;=&gt; CAST(#{vectorLiteral} AS vector))) AS score,
                   'SEMANTIC' AS match_kind
              FROM long_term_memories
             WHERE principal_id = #{principalId}
               AND status = 'ACTIVE'
               AND embedding IS NOT NULL
            <choose>
              <when test="scopeKey == null">AND scope_key IS NULL</when>
              <otherwise>AND (scope_key = #{scopeKey} OR (#{includeGlobal} AND scope_key IS NULL))</otherwise>
            </choose>
               AND (1 - (embedding &lt;=&gt; CAST(#{vectorLiteral} AS vector))) &gt;= #{minSimilarity}
             ORDER BY embedding &lt;=&gt; CAST(#{vectorLiteral} AS vector), memory_id
             LIMIT #{limit}
            </script>
            """)
    List<com.example.ragdemo.model.entity.MemorySearchHit> searchSemantic(
            @Param("principalId") String principalId, @Param("scopeKey") String scopeKey,
            @Param("includeGlobal") boolean includeGlobal, @Param("vectorLiteral") String vectorLiteral,
            @Param("minSimilarity") double minSimilarity, @Param("limit") int limit);

    /** 同槽位是否存在 CONFLICT 记录（用于在上下文中标注"存在争议"）。 */
    @Select("""
            <script>
            SELECT DISTINCT canonical_key FROM long_term_memories
             WHERE principal_id = #{principalId} AND status = 'CONFLICT'
               AND canonical_key IN
               <foreach collection="canonicalKeys" item="k" open="(" separator="," close=")">#{k}</foreach>
            </script>
            """)
    List<String> selectConflictSlots(@Param("principalId") String principalId,
            @Param("canonicalKeys") List<String> canonicalKeys);

    /** 同 principal/scope/type 下 ACTIVE 的 id（最多 2 条，用于判断"该类型是否唯一"）。 */
    @Select("""
            SELECT memory_id FROM long_term_memories
             WHERE principal_id = #{principalId} AND memory_type = #{memoryType} AND status = 'ACTIVE'
               AND COALESCE(scope_key, '') = COALESCE(#{scopeKey}, '')
             ORDER BY memory_id
             LIMIT 2
            """)
    List<Long> selectActiveIdsOfType(@Param("principalId") String principalId,
            @Param("scopeKey") String scopeKey, @Param("memoryType") String memoryType);

    /**
     * 跨槽 UPDATE 的候选：同 principal/scope/type 的其他 ACTIVE（排除新插入行），
     * 且正文向量相似度 ≥ 阈值；按相似度降序。相似度门槛在 SQL 层完成。
     */
    @Select("""
            <script>
            SELECT memory_id, memory_type, subject, predicate, content, canonical_key,
                   (1 - (embedding &lt;=&gt; CAST(#{vectorLiteral} AS vector))) AS score,
                   'CROSS_SLOT' AS match_kind
              FROM long_term_memories
             WHERE principal_id = #{principalId} AND memory_type = #{memoryType} AND status = 'ACTIVE'
               AND COALESCE(scope_key, '') = COALESCE(#{scopeKey}, '')
               AND memory_id &lt;&gt; #{excludeId}
               AND embedding IS NOT NULL
               AND (1 - (embedding &lt;=&gt; CAST(#{vectorLiteral} AS vector))) &gt;= #{minSimilarity}
             ORDER BY embedding &lt;=&gt; CAST(#{vectorLiteral} AS vector)
             LIMIT 5
            </script>
            """)
    List<com.example.ragdemo.model.entity.MemorySearchHit> searchUpdateCandidates(
            @Param("principalId") String principalId, @Param("scopeKey") String scopeKey,
            @Param("memoryType") String memoryType, @Param("vectorLiteral") String vectorLiteral,
            @Param("minSimilarity") double minSimilarity, @Param("excludeId") long excludeId);

    @Select("SELECT count(*) FROM long_term_memories WHERE principal_id = #{principalId} AND status = 'ACTIVE'")
    long countByPrincipalActive(@Param("principalId") String principalId);
}
