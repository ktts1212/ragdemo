package com.example.ragdemo.document.image;

/**
 * 图片资源存储抽象。
 * <p>
 * Marker 的 images 可能是很长的 base64。skill §15 强制：
 * <pre>
 * base64 → decode → FigureImageStorage → imageKey
 * </pre>
 * 禁止 base64 进入 AcademicDocument / JSON / DB。
 */
public interface FigureImageStorage {

    /**
     * 保存图片并返回资源 key（MinIO object key 或本地测试 key）。
     *
     * @param documentId  文档 ID（用于隔离目录）
     * @param blockId     来源 block id（用于命名与追溯）
     * @param imageBytes  解码后的图片字节
     * @param contentType MIME 类型，如 image/png；未知时可为 null
     * @return 资源 key（不含本地绝对路径）
     */
    String store(String documentId, String blockId, byte[] imageBytes, String contentType);
}
