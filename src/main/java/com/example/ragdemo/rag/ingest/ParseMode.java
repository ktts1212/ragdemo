package com.example.ragdemo.rag.ingest;

/**
 * 入库解析模式（skill academic_rag_ingestion_retrieval_qa §9）。
 * <p>
 * 三级决策（优先级由高到低）：
 * <pre>
 * 1. 上传参数（显式指定 ACADEMIC / GENERIC）
 * 2. 配置默认值（rag.parser.parse-mode）
 * 3. AUTO 自动判断：PDF → ACADEMIC；txt/md/其它 → GENERIC
 * </pre>
 */
public enum ParseMode {

    /** 自动判断（默认） */
    AUTO,

    /** 学术链路：Marker → Parser → Repair → Cleaner → SectionAwareChunker */
    ACADEMIC,

    /** 通用链路：LangChain4j parser → TextSplitterService */
    GENERIC;

    /** 宽松解析（大小写不敏感）；null / 空 / 非法值一律回退 {@link #AUTO}。 */
    public static ParseMode from(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        for (ParseMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return AUTO;
    }

    /** 是否已确定要走进学术链路（AUTO 未定，需继续判断）。 */
    public boolean isExplicitAcademic() {
        return this == ACADEMIC;
    }
}
