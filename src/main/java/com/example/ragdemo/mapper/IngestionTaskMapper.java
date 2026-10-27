package com.example.ragdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragdemo.model.entity.IngestionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface IngestionTaskMapper extends BaseMapper<IngestionTask> {

    @Update("UPDATE ingestion_tasks SET status = #{status}, error_message = #{errorMessage}, finished_at = #{finishedAt}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage,
                     @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 标记为 running。<b>Step 5 修正</b>：只对非终态生效 —— 重复投递的 Kafka 消息
     * 不得把已经 {@code success}/{@code failed} 的任务翻回 {@code running}（幂等保护）。
     */
    @Update("UPDATE ingestion_tasks SET status = 'running', started_at = COALESCE(started_at, NOW()), "
            + "updated_at = NOW() WHERE id = #{id} AND status NOT IN ('success', 'failed')")
    int markRunning(@Param("id") Long id);

    // ==================== Step 5：租约 / 原子抢占 / fencing ====================
    // 过期判断一律用数据库时钟（NOW()），避免多机时钟漂移。

    /**
     * 原子抢占（CAS，单条 UPDATE）：覆盖 {@code pending}、<b>退避已到期</b>的 {@code retry_wait}，
     * 以及「租约已过期或从未设置」的 {@code running}；成功时 token+1。
     * <p>
     * 不抢占 {@code success}/{@code failed}（终态）、{@code waiting}（等待人工确认），
     * 以及<b>退避未到期</b>的 {@code retry_wait}（避免瞬时错误被打成热循环）。
     *
     * @return 1=抢占成功，0=未获得（他人持有有效租约 / 退避未到期 / 状态不允许）
     */
    @Update("UPDATE ingestion_tasks SET lease_owner = #{owner}, "
            + "lease_expires_at = NOW() + (#{ttlSeconds} * INTERVAL '1 second'), "
            + "fencing_token = fencing_token + 1, status = 'running', "
            + "started_at = COALESCE(started_at, NOW()), attempt_count = attempt_count + 1, "
            + "next_retry_at = NULL, updated_at = NOW() "
            + "WHERE id = #{taskId} AND ("
            + "status = 'pending' "
            + "OR (status = 'retry_wait' AND (next_retry_at IS NULL OR next_retry_at <= NOW())) "
            + "OR (status = 'running' AND (lease_owner IS NULL OR lease_expires_at IS NULL "
            + "OR lease_expires_at < NOW())))")
    int tryAcquire(@Param("taskId") Long taskId, @Param("owner") String owner,
                   @Param("ttlSeconds") long ttlSeconds);

    @Select("SELECT fencing_token FROM ingestion_tasks WHERE id = #{taskId}")
    Long currentFencingToken(@Param("taskId") Long taskId);

    /** 续租：只允许 owner + 当前 token + running 续租（长任务不被误接管）。 */
    @Update("UPDATE ingestion_tasks SET lease_expires_at = NOW() + (#{ttlSeconds} * INTERVAL '1 second'), "
            + "updated_at = NOW() WHERE id = #{taskId} AND lease_owner = #{owner} "
            + "AND fencing_token = #{token} AND status = 'running'")
    int renewLease(@Param("taskId") Long taskId, @Param("owner") String owner,
                   @Param("token") long token, @Param("ttlSeconds") long ttlSeconds);

    /** 主动释放租约（不改变任务状态）。 */
    @Update("UPDATE ingestion_tasks SET lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW() "
            + "WHERE id = #{taskId} AND lease_owner = #{owner} AND fencing_token = #{token}")
    int releaseLease(@Param("taskId") Long taskId, @Param("owner") String owner,
                     @Param("token") long token);

    /** 仅在仍持有执行权时标记成功（旧 Worker 的迟到提交会被拒绝）。 */
    @Update("UPDATE ingestion_tasks SET status = 'success', progress = 100, error_message = NULL, "
            + "last_error_code = NULL, next_retry_at = NULL, finished_at = NOW(), "
            + "lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW() "
            + "WHERE id = #{taskId} AND lease_owner = #{owner} AND fencing_token = #{token} "
            + "AND status = 'running'")
    int markSucceededIfOwner(@Param("taskId") Long taskId, @Param("owner") String owner,
                             @Param("token") long token);

    /**
     * 仅在仍持有执行权时标记失败或进入有限重试等待。
     *
     * @param status          {@code failed}（永久）或 {@code retry_wait}（瞬时，可再抢）
     * @param backoffSeconds  &gt;0 时设置 next_retry_at
     */
    @Update("UPDATE ingestion_tasks SET status = #{status}, error_message = #{errorMessage}, "
            + "last_error_code = #{errorCode}, "
            + "next_retry_at = CASE WHEN #{backoffSeconds} > 0 "
            + "THEN NOW() + (#{backoffSeconds} * INTERVAL '1 second') ELSE NULL END, "
            + "finished_at = CASE WHEN #{status} = 'failed' THEN NOW() ELSE NULL END, "
            + "lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW() "
            + "WHERE id = #{taskId} AND lease_owner = #{owner} AND fencing_token = #{token} "
            + "AND status = 'running'")
    int markFailedOrRetryIfOwner(@Param("taskId") Long taskId, @Param("owner") String owner,
                                @Param("token") long token, @Param("status") String status,
                                @Param("errorCode") String errorCode,
                                @Param("errorMessage") String errorMessage,
                                @Param("backoffSeconds") long backoffSeconds);

    /** 进入 waiting（等待人工确认）：释放租约但保留任务，不被自动恢复抢占。 */
    @Update("UPDATE ingestion_tasks SET status = 'waiting', error_message = #{errorMessage}, "
            + "last_error_code = #{errorCode}, lease_owner = NULL, lease_expires_at = NULL, "
            + "updated_at = NOW() WHERE id = #{taskId} AND lease_owner = #{owner} "
            + "AND fencing_token = #{token} AND status = 'running'")
    int markWaitingIfOwner(@Param("taskId") Long taskId, @Param("owner") String owner,
                           @Param("token") long token, @Param("errorCode") String errorCode,
                           @Param("errorMessage") String errorMessage);

    /**
     * 待恢复任务候选（Step 5 §5 调度器使用）：{@code pending}、退避已到期的 {@code retry_wait}、
     * 以及<b>租约已过期</b>的 {@code running}。
     * <p>
     * 与 {@link #tryAcquire} 的判据保持一致 ⇒ <b>不会把当前存活的执行者抢掉</b>；
     * {@code waiting}（等待人工确认）与终态不参与扫描。
     */
    @Select("SELECT * FROM ingestion_tasks WHERE status = 'pending' "
            + "OR (status = 'retry_wait' AND (next_retry_at IS NULL OR next_retry_at <= NOW())) "
            + "OR (status = 'running' AND (lease_owner IS NULL OR lease_expires_at IS NULL "
            + "OR lease_expires_at < NOW())) "
            + "ORDER BY created_at LIMIT #{limit}")
    java.util.List<IngestionTask> findRecoverable(@Param("limit") int limit);

    /**
     * <b>fencing 核心</b>：同一事务内校验 owner + token + status='running' 并<b>锁行</b>
     * （{@code SELECT ... FOR UPDATE}）。返回非 null 表示通过；返回 null 表示执行权已被接管/状态不符。
     * <p>
     * 必须在与业务写入（{@code replaceChunks}）<b>同一个事务</b>内调用，
     * 这样"校验通过 ⇒ 写入提交"之间不存在 TOCTOU 窗口：并发的抢占 UPDATE 会被行锁阻塞。
     */
    @Select("SELECT fencing_token FROM ingestion_tasks "
            + "WHERE id = #{taskId} AND lease_owner = #{owner} AND fencing_token = #{token} "
            + "AND status = 'running' FOR UPDATE")
    Long lockOwnedToken(@Param("taskId") Long taskId, @Param("owner") String owner,
                        @Param("token") long token);
}
