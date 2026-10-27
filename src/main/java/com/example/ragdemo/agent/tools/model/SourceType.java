package com.example.ragdemo.agent.tools.model;

/**
 * 证据"来源"维度（Step 2 §4）。
 * <p>
 * 与 {@link ExtractionMethod} 正交：来源回答"内容来自哪一层产物"，
 * 提取方法回答"用什么手段读出来的"。不得把 OCR 误当成来源类型。
 */
public enum SourceType {

    /** 已入库的 pgvector chunk（检索层产物） */
    CHUNK,

    /** Canonical AcademicDocument（结构化解析产物，MinIO canonical.json） */
    CANONICAL,

    /** 原始 PDF（MinIO source/original.pdf） */
    ORIGINAL_PDF,

    /** 论文目录（documents 表元数据，非正文证据；Agent 引用清单时使用） */
    CATALOG
}
