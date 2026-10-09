package com.example.ragdemo.infrastructure.marker;

/**
 * Marker API 客户端接口：定义暴露给上层解析链路的能力。
 * <p>
 * 本层只负责 HTTP 通信（PDF bytes → Raw Marker JSON），
 * 不解析 Document Tree、不做结构映射、不落库。
 * <p>
 * 说明：输入采用内存字节 + 文件名（与项目既有 GrobidClient 模式一致）；
 * 存储命名属于 MinIO/storage 层，不污染本层。
 */
public interface MarkerClient {

    /**
     * 调用 Marker /api/v1/parse 解析 PDF。
     *
     * @param pdfBytes PDF 字节内容，不能为 null 或空
     * @param fileName PDF 文件名
     * @return Marker 原始响应（含原始 JSON data），不会返回 null
     * @throws com.example.ragdemo.infrastructure.marker.exception.MarkerException
     *         及其子类：超时 / 不可用 / 处理失败 / 无效响应
     */
    MarkerResponse parse(byte[] pdfBytes, String fileName);

    /**
     * Marker 健康检查（GET /health）。
     *
     * @throws com.example.ragdemo.infrastructure.marker.exception.MarkerUnavailableException
     *         Marker 不可达或健康检查失败
     */
    MarkerHealth health();

    /**
     * 轻量存活检查：任何异常都返回 false，不抛出。
     */
    boolean isAlive();
}
