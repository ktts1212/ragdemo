package com.example.ragdemo.agent.tools.model;

/**
 * 证据"提取方法"维度（Step 2 §4）。
 */
public enum ExtractionMethod {

    /** 直接读已入库的索引 chunk */
    INDEXED_CHUNK,

    /** 读 Canonical 结构化解析结果（Parser/Cleaner 产物） */
    CANONICAL_PARSE,

    /** 用 PDF 文本层按页提取（PDFBox） */
    PDF_TEXT,

    /** OCR（本步未实现，仅用于标记缺口） */
    OCR,

    /** 页面图像渲染（受控页图引用） */
    IMAGE_RENDER,

    /** 目录类查询（documents 元数据） */
    CATALOG_QUERY
}
