package com.example.ragdemo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.example.ragdemo.model.entity.AgentRun;

/**
 * Agent Run Registry Mapper（Step 4 §3）。
 * <p>
 * 只做 <b>身份/执行权/幂等</b> 的原子操作，不存原文、句柄、密钥：
 * <ul>
 *   <li>{@link #insertRun} + {@code uk_agent_runs_request_key} 偏唯一索引 ⇒ 同一 requestKey 只生成一条 Run；</li>
 *   <li>{@link #claimRun} 单条 UPDATE 乐观并发抢占 ⇒ 同 Run 不允许两个执行者同时 invoke；</li>
 *   <li>{@link #claimMemoryCommit} 单条 UPDATE ⇒ 本轮问答最多写入会话记忆一次（崩溃恢复不重复）；</li>
 *   <li>{@link #touch} 递增 revision，暴露给使用者观测。</li>
 * </ul>
 */
@Mapper
public interface AgentRunMapper {

    @Insert("""
            INSERT INTO agent_runs (run_id, conversation_id, knowledge_base_id, principal_id, request_key,
                                    thread_id, status, question_digest, memory_committed, revision,
                                    created_at, updated_at)
            VALUES (#{runId}, #{conversationId}, #{knowledgeBaseId}, #{principalId}, #{requestKey},
                    #{threadId}, 'CREATED', #{questionDigest}, FALSE, 0, NOW(), NOW())
            """)
    int insertRun(AgentRun run);

    @Select("SELECT * FROM agent_runs WHERE run_id = #{runId}")
    AgentRun selectByRunId(@Param("runId") String runId);

    @Select("SELECT * FROM agent_runs WHERE request_key = #{requestKey} LIMIT 1")
    AgentRun selectByRequestKey(@Param("requestKey") String requestKey);

    @Select("SELECT * FROM agent_runs WHERE conversation_id = #{conversationId} ORDER BY created_at DESC LIMIT #{limit}")
    List<AgentRun> selectRecentByConversation(@Param("conversationId") String conversationId,
            @Param("limit") int limit);

    /**
     * 原子抢占：仅当状态允许且 revision 一致时成功。
     *
     * @return 1 = 抢占成功（本次执行者拥有该 Run），0 = 被他人持有或版本已变
     */
    @Update("""
            UPDATE agent_runs
               SET status = 'RUNNING', revision = revision + 1, updated_at = NOW()
             WHERE run_id = #{runId}
               AND revision = #{expectedRevision}
               AND status IN ('CREATED', 'INTERRUPTED')
            """)
    int claimRun(@Param("runId") String runId, @Param("expectedRevision") int expectedRevision);

    @Update("""
            UPDATE agent_runs
               SET status = #{status}, last_error_code = #{errorCode}, revision = revision + 1,
                   updated_at = NOW(),
                   completed_at = CASE WHEN #{status} IN ('COMPLETED', 'FAILED') THEN NOW() ELSE completed_at END
             WHERE run_id = #{runId}
            """)
    int updateStatus(@Param("runId") String runId, @Param("status") String status,
            @Param("errorCode") String errorCode);

    /** 记忆提交幂等令牌：只有把 false 翻成 true 的那次才允许写会话记忆。 */
    @Update("""
            UPDATE agent_runs
               SET memory_committed = TRUE, updated_at = NOW()
             WHERE run_id = #{runId} AND memory_committed = FALSE
            """)
    int claimMemoryCommit(@Param("runId") String runId);

    // ==================== Step 4E：执行租约（新增，不影响上面的旧方法） ====================

    /**
     * 带租约的执行权抢占（单条 CAS，过期判定交给数据库时钟）。
     * <p>
     * 允许接管的情形：{@code CREATED / INTERRUPTED}，或 {@code RUNNING 且租约已过期}（崩溃进程留下的
     * RUNNING 因此不会永久阻塞）。成功时 {@code fencing_token + 1}；若本轮是从过期 RUNNING 接管，
     * 则 {@code generation + 1}（记录"被接管次数"）。
     *
     * @return 1 = 抢占成功（本执行者持有租约），0 = 他人持有有效租约或状态不允许
     */
    @Update("""
            UPDATE agent_runs
               SET status = 'RUNNING',
                   lease_owner = #{owner},
                   lease_expires_at = NOW() + (#{ttlSeconds} * INTERVAL '1 second'),
                   fencing_token = fencing_token + 1,
                   generation = CASE WHEN status = 'RUNNING' THEN generation + 1 ELSE generation END,
                   revision = revision + 1,
                   updated_at = NOW()
             WHERE run_id = #{runId}
               AND ( status IN ('CREATED', 'INTERRUPTED')
                     OR (status = 'RUNNING' AND lease_expires_at IS NOT NULL
                         AND lease_expires_at < NOW()) )
            """)
    int tryAcquireLease(@Param("runId") String runId, @Param("owner") String owner,
            @Param("ttlSeconds") long ttlSeconds);

    /** 续租：只有当前 owner + token 才能续。 */
    @Update("""
            UPDATE agent_runs
               SET lease_expires_at = NOW() + (#{ttlSeconds} * INTERVAL '1 second'), updated_at = NOW()
             WHERE run_id = #{runId} AND lease_owner = #{owner} AND fencing_token = #{token}
            """)
    int renewLease(@Param("runId") String runId, @Param("owner") String owner,
            @Param("token") long token, @Param("ttlSeconds") long ttlSeconds);

    /**
     * owner + token 保护的状态写入：<b>失去执行权的旧 Worker 的迟到写入会被拒绝</b>。
     * 终态时清空租约字段。
     */
    @Update("""
            UPDATE agent_runs
               SET status = #{status}, last_error_code = #{errorCode}, revision = revision + 1,
                   updated_at = NOW(),
                   completed_at = CASE WHEN #{status} IN ('COMPLETED', 'FAILED') THEN NOW() ELSE completed_at END,
                   lease_owner = CASE WHEN #{status} IN ('COMPLETED', 'FAILED') THEN NULL ELSE lease_owner END,
                   lease_expires_at = CASE WHEN #{status} IN ('COMPLETED', 'FAILED') THEN NULL ELSE lease_expires_at END
             WHERE run_id = #{runId} AND lease_owner = #{owner} AND fencing_token = #{token}
            """)
    int updateStatusIfOwner(@Param("runId") String runId, @Param("owner") String owner,
            @Param("token") long token, @Param("status") String status, @Param("errorCode") String errorCode);

    /** owner + token 保护的记忆提交令牌（过期执行者拿不到）。 */
    @Update("""
            UPDATE agent_runs
               SET memory_committed = TRUE, updated_at = NOW()
             WHERE run_id = #{runId} AND memory_committed = FALSE
               AND lease_owner = #{owner} AND fencing_token = #{token}
            """)
    int claimMemoryCommitIfOwner(@Param("runId") String runId, @Param("owner") String owner,
            @Param("token") long token);

    @Select("SELECT fencing_token FROM agent_runs WHERE run_id = #{runId}")
    Long currentFencingToken(@Param("runId") String runId);

    @Select("SELECT generation FROM agent_runs WHERE run_id = #{runId}")
    Integer currentGeneration(@Param("runId") String runId);
}
