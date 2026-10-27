package com.example.ragdemo.workflow.runtime;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 失败分类器（Step 5 §5）：把异常分成 <b>永久 / 瞬时 / 执行权丢失</b>，
 * 决定任务是进入 {@code failed} 终态、{@code retry_wait} 有限重试，还是"什么都不写，交给新持有者"。
 * <p>
 * 判据刻意与 Step 4 实际抛出的错误码对齐；不确定的一律归为瞬时（有限重试 + 退避，
 * 超过阈值由人工处理），避免把永久错误打成无限重试。
 */
@Component
public class FailureClassifier {

    public enum Kind {
        /** 不可恢复：源文件缺失、canonical hard-invalid、无 chunk、状态不一致、参数非法等 */
        PERMANENT,
        /** 可恢复：连接/超时/5xx 等，有限重试 + 退避 */
        TRANSIENT,
        /** 执行权已被他人接管：<b>不得</b>写任务状态（否则会破坏新持有者的状态机） */
        LEASE_LOST
    }

    public record Result(Kind kind, String code) {
    }

    private static final List<String> PERMANENT_MARKERS = List.of(
            "ACADEMIC_DOWNLOAD_FAILED",
            "ACADEMIC_INVALID_CANONICAL",
            "ACADEMIC_NO_CHUNKS",
            "ACADEMIC_MISSING_CHECKSUM",
            "INGESTION_STATE_MISMATCH",
            "INGESTION_ARTIFACT_READ_FAILED",
            "IllegalArgumentException");

    private static final String LEASE_LOST_MARKER = "FENCING_REJECTED";

    public Result classify(Throwable throwable) {
        String message = rootMessages(throwable);
        if (message.contains(LEASE_LOST_MARKER)) {
            return new Result(Kind.LEASE_LOST, "LEASE_LOST");
        }
        for (String marker : PERMANENT_MARKERS) {
            if (message.contains(marker)) {
                return new Result(Kind.PERMANENT, marker);
            }
        }
        return new Result(Kind.TRANSIENT, "TRANSIENT");
    }

    /** 汇总异常链上的 message（根因往往在最内层，例如 JDBC/HTTP 超时）。 */
    private static String rootMessages(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current.getMessage() != null) {
                builder.append(current.getMessage()).append(" | ");
            }
            current = current.getCause();
        }
        return builder.toString();
    }
}
