package com.example.ragdemo.document.parser;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 学术文档解析器统一抽象。
 * <p>
 * 业务层只依赖本接口，不直接依赖具体解析后端（Marker / Grobid）。
 * 未来更换解析器只需新增实现，上层与领域模型不变。
 */
public interface AcademicDocumentParser {

    /**
     * 把解析后端返回的原始文档结构转换为统一 {@link AcademicDocument}。
     *
     * @param documentId  系统现有 documents.id
     * @param rawDocument 解析后端原始结构（Marker 为 Document Tree JSON）
     * @return 统一学术文档对象
     */
    AcademicDocument parse(String documentId, JsonNode rawDocument);
}
