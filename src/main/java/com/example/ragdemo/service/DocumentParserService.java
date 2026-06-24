package com.example.ragdemo.service;

import java.io.InputStream;

/**
 * 文档解析服务：根据文件扩展名选择对应的解析器
 */
public interface DocumentParserService {

    /**
     * 解析文档输入流，提取纯文本
     *
     * @param inputStream 文件输入流（从 MinIO 下载）
     * @param fileExt     文件扩展名（.pdf / .docx / .txt / .md）
     * @return 提取的纯文本
     */
    String parse(InputStream inputStream, String fileExt);
}
