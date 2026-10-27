package com.example.ragdemo.agent.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ragdemo.agent.api.AgentRequestException;
import com.example.ragdemo.mapper.AgentRunMapper;
import com.example.ragdemo.model.entity.AgentRun;

import lombok.extern.slf4j.Slf4j;

/**
 * Agent Run Registry（Step 4 §3）：运行身份、HTTP 重试幂等、执行权互斥、记忆提交幂等。
 * <p>
 * 三个实体语义严格分离：{@code conversationId}（跨请求会话）/ {@code runId}（本次执行）/
 * {@code threadId = "agent-"+runId}（本轮 checkpoint 身份，UNIQUE）。
 * <p>
 * 安全边界（当前项目无用户级 RBAC）：
 * <ul>
 *   <li>只允许通过 <b>服务端授权的 kb + 会话归属</b> 访问 Run，绝不凭可猜测的 runId 直接读取/恢复；</li>
 *   <li>Checkpoint 里的 kbId 只用于<b>一致性校验</b>，绝不用于创建 Toolkit；</li>
 *   <li>不区分"不存在"与"无权访问"，统一 {@code AGENT_RUN_NOT_FOUND_OR_FORBIDDEN}。</li>
 * </ul>
 */
@Slf4j
@Service
public class AgentRunRegistry {

    public static final String THREAD_PREFIX = "agent-";

    private final AgentRunMapper mapper;

    public AgentRunRegistry(AgentRunMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 幂等创建/取回 Run：同一 {@code requestKey} 重复投递（HTTP 重试）不会产生第二条 Run。
     * <p>并发同 key 时依赖偏唯一索引兜底（捕获唯一键冲突后回读）。
     */
    @Transactional
    public AgentRun createOrGet(String requestKey, String conversationId, long knowledgeBaseId,
            String principalId, String question) {
        String key = (requestKey == null || requestKey.isBlank()) ? null : requestKey.trim();
        if (key != null) {
            AgentRun existing = mapper.selectByRequestKey(key);
            if (existing != null) {
                log.info("[agent-run] 重试命中既有 Run: runId={}", existing.getRunId());
                return existing;
            }
        }
        AgentRun run = new AgentRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setConversationId(conversationId);
        run.setKnowledgeBaseId(knowledgeBaseId);
        run.setPrincipalId(principalId);
        run.setRequestKey(key);
        run.setThreadId(THREAD_PREFIX + run.getRunId());
        run.setStatus("CREATED");
        run.setQuestionDigest(digest(question));
        run.setMemoryCommitted(Boolean.FALSE);
        run.setRevision(0);
        try {
            mapper.insertRun(run);
        } catch (DuplicateKeyException e) {
            AgentRun existing = mapper.selectByRequestKey(key);
            if (existing == null) {
                throw e;
            }
            log.info("[agent-run] 并发同 requestKey，回读既有 Run: runId={}", existing.getRunId());
            return existing;
        }
        return mapper.selectByRunId(run.getRunId());
    }

    /**
     * 按服务端授权范围读取 Run；越权与不存在返回同一个错误（不泄漏存在性）。
     *
     * @param authorizedKbId           本次请求经认证的 kb（来自服务端白名单，不来自 Checkpoint）
     * @param authorizedConversationId 本次请求的会话（可空：为空时不校验会话归属）
     */
    public AgentRun requireAuthorizedRun(String runId, long authorizedKbId, String authorizedConversationId) {
        AgentRun run = runId == null ? null : mapper.selectByRunId(runId);
        if (run == null) {
            throw notFoundOrForbidden();
        }
        if (run.getKnowledgeBaseId() == null || run.getKnowledgeBaseId() != authorizedKbId) {
            log.warn("[agent-run] 拒绝跨知识库访问: runId={}, runKb={}, authorizedKb={}",
                    runId, run.getKnowledgeBaseId(), authorizedKbId);
            throw notFoundOrForbidden();
        }
        if (authorizedConversationId != null && !authorizedConversationId.isBlank()
                && run.getConversationId() != null
                && !authorizedConversationId.equals(run.getConversationId())) {
            log.warn("[agent-run] 拒绝跨会话访问: runId={}", runId);
            throw notFoundOrForbidden();
        }
        return run;
    }

    /** 原子抢占执行权（同 Run 不允许两个执行者并发 invoke）。 */
    public boolean claim(AgentRun run) {
        int updated = mapper.claimRun(run.getRunId(), run.getRevision() == null ? 0 : run.getRevision());
        if (updated != 1) {
            log.warn("[agent-run] 抢占失败（已被其它执行者持有或版本已变）: runId={}, revision={}",
                    run.getRunId(), run.getRevision());
            return false;
        }
        return true;
    }

    /**
     * 状态写入（<b>无 owner/token 校验</b>）。
     *
     * @deprecated <b>Durable 执行链路禁止调用</b>：这是无条件写入，会被失去执行权的旧执行者滥用。
     *     Durable 流程一律使用 {@link #markStatus(Lease, String, String)}。保留仅为历史/非 Durable
     *     路径与既有测试。
     */
    @Deprecated(since = "4E-2b")
    public void markStatus(String runId, String status, String errorCode) {
        mapper.updateStatus(runId, status, errorCode);
    }

    /**
     * 记忆提交幂等令牌（<b>无 owner/token 校验</b>）：只有返回 true 的那一次允许写会话记忆。
     *
     * @deprecated <b>Durable 执行链路禁止调用</b>：Durable 流程一律使用 {@link #claimMemoryCommit(Lease)}，
     *     否则失去执行权的旧执行者可能替新执行者写会话记忆。保留仅为历史/非 Durable 路径与既有测试。
     */
    @Deprecated(since = "4E-2b")
    public boolean claimMemoryCommit(String runId) {
        return mapper.claimMemoryCommit(runId) == 1;
    }

    public AgentRun reload(String runId) {
        return mapper.selectByRunId(runId);
    }

    // ==================== Step 4E：执行租约（新增） ====================

    /**
     * 一次成功抢占得到的执行权凭据（owner + fencing token + generation）。
     * <p>所有"该执行者才能做"的写入都必须带这两个值，否则失去执行权的旧 Worker 仍能提交结果。
     */
    public record Lease(String runId, String owner, long fencingToken, int generation) {
    }

    /** 生成本次执行的 Worker 唯一 id（进程内随机，不入 Checkpoint）。 */
    public static String newOwner(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /**
     * 抢占用执行权（含"过期 RUNNING 可被接管"）。
     *
     * @param ttlSeconds 租约 TTL；{@code 0} 表示"立即过期"（用于测试与人工接管）
     */
    public java.util.Optional<Lease> tryAcquireLease(String runId, String owner, long ttlSeconds) {
        int updated = mapper.tryAcquireLease(runId, owner, ttlSeconds);
        if (updated != 1) {
            log.info("[agent-lease] 抢占未成功（他人持有有效租约或状态不允许）: runId={}, owner={}", runId, owner);
            return java.util.Optional.empty();
        }
        Long token = mapper.currentFencingToken(runId);
        Integer generation = mapper.currentGeneration(runId);
        Lease lease = new Lease(runId, owner, token == null ? -1L : token, generation == null ? 0 : generation);
        log.info("[agent-lease] 抢占成功: runId={}, owner={}, token={}, generation={}", runId, owner,
                lease.fencingToken(), lease.generation());
        return java.util.Optional.of(lease);
    }

    /** 续租：只有当前 owner + token 生效（心跳用）。 */
    public boolean renewLease(Lease lease, long ttlSeconds) {
        return mapper.renewLease(lease.runId(), lease.owner(), lease.fencingToken(), ttlSeconds) == 1;
    }

    /**
     * owner + token 保护的终态/状态写入 —— <b>过期执行者的迟到写入被拒绝</b>（返回 false）。
     */
    public boolean markStatus(Lease lease, String status, String errorCode) {
        boolean ok = mapper.updateStatusIfOwner(lease.runId(), lease.owner(), lease.fencingToken(), status,
                errorCode) == 1;
        if (!ok) {
            log.warn("[agent-lease] 状态写入被拒绝（执行权已易主/租约失效）: runId={}, owner={}, token={}",
                    lease.runId(), lease.owner(), lease.fencingToken());
        }
        return ok;
    }

    /** owner + token 保护的记忆提交令牌（过期执行者拿不到 ⇒ 不会替别人写记忆）。 */
    public boolean claimMemoryCommit(Lease lease) {
        boolean ok = mapper.claimMemoryCommitIfOwner(lease.runId(), lease.owner(), lease.fencingToken()) == 1;
        if (!ok) {
            log.info("[agent-lease] 记忆提交令牌未获得（票据已用或执行权已易主）: runId={}", lease.runId());
        }
        return ok;
    }

    private static AgentRequestException notFoundOrForbidden() {
        return new AgentRequestException(404, "AGENT_RUN_NOT_FOUND_OR_FORBIDDEN",
                "该运行不存在或不在当前授权范围内");
    }

    private static String digest(String question) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((question == null ? "" : question).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception e) {
            return null;
        }
    }
}
