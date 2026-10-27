package com.example.ragdemo.workflow.ingestion;

/**
 * 入库图的错误码前缀（写入 {@code documents.error_message} / {@code ingestion_tasks.error_message}）。
 * <p>
 * <b>为什么重复这几个常量</b>：旧链路 {@code AcademicIngestionService} 里的同名常量是
 * {@code private}，而 step4 §0.2/§2 要求最小侵入、不动旧链路行为，因此这里保留<b>逐字相同</b>的
 * 前四个前缀以保证失败语义等价；后三个是图编排新增的阶段。
 */
public final class IngestionErrorCodes {

    /** 与 AcademicIngestionService 逐字一致 */
    public static final String DOWNLOAD_FAILED = "ACADEMIC_DOWNLOAD_FAILED: ";
    public static final String MISSING_CHECKSUM = "ACADEMIC_MISSING_CHECKSUM: ";
    public static final String INVALID_CANONICAL = "ACADEMIC_INVALID_CANONICAL: ";
    public static final String NO_CHUNKS = "ACADEMIC_NO_CHUNKS: ";

    /** 图编排新增 */
    public static final String ARTIFACT_READ_FAILED = "INGESTION_ARTIFACT_READ_FAILED: ";
    public static final String EMBEDDING_FAILED = "INGESTION_EMBEDDING_FAILED: ";
    public static final String STATE_MISMATCH = "INGESTION_STATE_MISMATCH: ";

    private IngestionErrorCodes() {
    }
}
