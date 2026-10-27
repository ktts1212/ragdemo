package com.example.ragdemo.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.example.ragdemo.model.entity.MemoryExtractionJob;

/**
 * L3 记忆提取任务 Mapper（Memory V1 · Phase 2A）。
 * <p>并发控制：候选集用 {@code FOR UPDATE SKIP LOCKED} 在事务内锁定；状态写入一律带
 * {@code lease_owner + generation} 条件 ⇒ 过期 Worker 的迟到提交必然 0 行。
 */
@Mapper
public interface MemoryExtractionJobMapper {

    /** 幂等入队：同 idempotency_key 只保留一条（返回 1 = 本次创建，0 = 已存在）。 */
    @Insert("""
            INSERT INTO memory_extraction_jobs
                (job_id, principal_id, conversation_id, source_message_id, source_run_id,
                 idempotency_key, extractor_version, status, attempts, created_at, updated_at)
            VALUES (nextval('memory_extraction_job_seq'), #{principalId}, #{conversationId},
                    #{sourceMessageId}, #{sourceRunId}, #{idempotencyKey}, #{extractorVersion},
                    'PENDING', 0, NOW(), NOW())
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertIfAbsent(@Param("principalId") String principalId,
            @Param("conversationId") String conversationId,
            @Param("sourceMessageId") String sourceMessageId,
            @Param("sourceRunId") String sourceRunId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("extractorVersion") String extractorVersion);

    /**
     * 可领取的候选（必须在事务内调用）：待办到期的 PENDING/FAILED，以及租约过期的 RUNNING。
     * {@code FOR UPDATE SKIP LOCKED} 保证多 Worker 并发领取互斥。
     */
    @Select("""
            SELECT job_id FROM memory_extraction_jobs
             WHERE principal_id = #{principalId}
               AND ( (status IN ('PENDING', 'FAILED') AND (next_retry_at IS NULL OR next_retry_at <= NOW()))
                     OR (status = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at < NOW()) )
             ORDER BY created_at
             LIMIT #{limit}
             FOR UPDATE SKIP LOCKED
            """)
    List<Long> selectClaimable(@Param("principalId") String principalId, @Param("limit") int limit);

    /**
     * 领取并<b>只返回本次真正领取成功</b>的行（PG 的 {@code UPDATE … RETURNING}）。
     * <p>返回的 {@code lease_owner / generation / status} 就是更新后的真实值，调用方据此提交成败，
     * 不会把"仍处于有效租约期的 RUNNING"再返回一次。
     */
    @Select("""
            <script>
            UPDATE memory_extraction_jobs
               SET status = 'RUNNING',
                   lease_owner = #{owner},
                   lease_expires_at = NOW() + (#{ttlSeconds} * INTERVAL '1 second'),
                   generation = generation + 1,
                   attempts = attempts + 1,
                   updated_at = NOW()
             WHERE job_id IN
             <foreach collection="jobIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND ( (status IN ('PENDING','FAILED') AND (next_retry_at IS NULL OR next_retry_at &lt;= NOW()))
                     OR (status = 'RUNNING' AND lease_expires_at &lt; NOW()) )
            RETURNING job_id, generation, lease_owner, status
            </script>
            """)
    List<MemoryExtractionJob> claimAndReturn(@Param("jobIds") List<Long> jobIds, @Param("owner") String owner,
            @Param("ttlSeconds") long ttlSeconds);

    /**
     * 读回“本次领取”的行：owner 匹配 + status=RUNNING + {@code updated_at >= 本次 claim 开始时刻}。
     * <p>用 {@code @Update} 执行 claim（已证落库）+ 本查询回读，规避 {@code @Select} 执行
     * {@code UPDATE … RETURNING} 在本工程 MyBatis 配置下不落库的问题（已实测）。
     */
    @Select("""
            <script>
            SELECT * FROM memory_extraction_jobs
             WHERE job_id IN
             <foreach collection="jobIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND lease_owner = #{owner}
               AND status = 'RUNNING'
               AND updated_at &gt;= #{since}
            </script>
            """)
    List<MemoryExtractionJob> selectClaimed(@Param("jobIds") List<Long> jobIds, @Param("owner") String owner,
            @Param("since") LocalDateTime since);

    /** 领取：置 RUNNING、写持有者与租约、generation+1、attempts+1。 */
    @Update("""
            <script>
            UPDATE memory_extraction_jobs
               SET status = 'RUNNING',
                   lease_owner = #{owner},
                   lease_expires_at = NOW() + (#{ttlSeconds} * INTERVAL '1 second'),
                   generation = generation + 1,
                   attempts = attempts + 1,
                   updated_at = NOW()
             WHERE job_id IN
             <foreach collection="jobIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND principal_id = #{principalId}
               AND ( (status IN ('PENDING','FAILED') AND (next_retry_at IS NULL OR next_retry_at &lt;= NOW()))
                     OR (status = 'RUNNING' AND lease_expires_at &lt; NOW()) )
            </script>
            """)
    int claim(@Param("jobIds") List<Long> jobIds, @Param("owner") String owner,
            @Param("ttlSeconds") long ttlSeconds, @Param("principalId") String principalId);

    @Select("SELECT * FROM memory_extraction_jobs WHERE job_id = #{jobId}")
    MemoryExtractionJob selectById(@Param("jobId") long jobId);

    /** 成功：必须仍是本次领取的 owner + generation。 */
    @Update("""
            UPDATE memory_extraction_jobs
               SET status = 'DONE', last_error = NULL, next_retry_at = NULL,
                   lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW()
             WHERE job_id = #{jobId} AND lease_owner = #{owner} AND generation = #{generation}
               AND status = 'RUNNING'
            """)
    int markDone(@Param("jobId") long jobId, @Param("owner") String owner,
            @Param("generation") int generation);

    /** 失败：尝试次数达上限 ⇒ DEAD，否则 FAILED + 退避；同样受 owner+generation 保护。 */
    @Update("""
            UPDATE memory_extraction_jobs
               SET status = CASE WHEN attempts >= #{maxAttempts} THEN 'DEAD' ELSE 'FAILED' END,
                   last_error = #{error},
                   next_retry_at = CASE WHEN attempts >= #{maxAttempts} THEN NULL
                                        ELSE NOW() + (#{backoffSeconds} * INTERVAL '1 second') END,
                   lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW()
             WHERE job_id = #{jobId} AND lease_owner = #{owner} AND generation = #{generation}
               AND status = 'RUNNING'
            """)
    int markFailed(@Param("jobId") long jobId, @Param("owner") String owner,
            @Param("generation") int generation, @Param("error") String error,
            @Param("maxAttempts") int maxAttempts, @Param("backoffSeconds") long backoffSeconds);

    /** 崩溃恢复：租约过期的 RUNNING ⇒ attempts 达上限则 DEAD，否则回到 PENDING。 */
    @Update("""
            UPDATE memory_extraction_jobs
               SET status = CASE WHEN attempts >= #{maxAttempts} THEN 'DEAD' ELSE 'PENDING' END,
                   lease_owner = NULL, lease_expires_at = NULL,
                   next_retry_at = NULL,
                   last_error = COALESCE(last_error, 'LEASE_EXPIRED'),
                   updated_at = NOW()
             WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at < NOW()
            """)
    int recoverExpired(@Param("maxAttempts") int maxAttempts);

    @Select("SELECT count(*) FROM memory_extraction_jobs WHERE idempotency_key = #{key}")
    long countByKey(@Param("key") String key);

    @Select("SELECT count(*) FROM memory_extraction_jobs WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    /** 取会话中最新一条 user 消息 id（入队幂等键的消息标识；没有则 null）。 */
    @Select("""
            SELECT id FROM chat_messages
             WHERE conversation_id = #{conversationId} AND role = 'user'
             ORDER BY created_at DESC, id DESC LIMIT 1
            """)
    String selectLatestUserMessageId(@Param("conversationId") String conversationId);

    @Select("SELECT * FROM memory_extraction_jobs WHERE status = #{status} ORDER BY created_at LIMIT #{limit}")
    List<MemoryExtractionJob> selectByStatus(@Param("status") String status, @Param("limit") int limit);

    /** 测试辅助：把租约置为已过期（仅测试使用，生产不调用）。 */
    @Update("""
            UPDATE memory_extraction_jobs SET lease_expires_at = NOW() - INTERVAL '1 second'
             WHERE job_id = #{jobId}
            """)
    int forceLeaseExpired(@Param("jobId") long jobId);

    @Update("UPDATE memory_extraction_jobs SET next_retry_at = #{at} WHERE job_id = #{jobId}")
    int setNextRetryAt(@Param("jobId") long jobId, @Param("at") LocalDateTime at);
}
