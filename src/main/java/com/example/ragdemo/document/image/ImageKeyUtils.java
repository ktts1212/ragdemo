package com.example.ragdemo.document.image;

/**
 * 图片 key / 类型工具（包级，供各存储实现复用）。
 */
final class ImageKeyUtils {

    private ImageKeyUtils() {
    }

    /**
     * key 格式：figures/{documentId}/{safeBlockId}.{ext}
     */
    static String buildKey(String documentId, String blockId, String contentType) {
        String safeDoc = sanitize(documentId == null ? "unknown-doc" : documentId);
        String safeBlock = sanitize(blockId == null ? "image" : blockId);
        return "figures/" + safeDoc + "/" + safeBlock + "." + extensionOf(contentType);
    }

    private static String sanitize(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    static String extensionOf(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "bin";
        }
        String lower = contentType.toLowerCase();
        if (lower.contains("png")) {
            return "png";
        }
        if (lower.contains("jpeg") || lower.contains("jpg")) {
            return "jpg";
        }
        if (lower.contains("gif")) {
            return "gif";
        }
        if (lower.contains("webp")) {
            return "webp";
        }
        if (lower.contains("svg")) {
            return "svg";
        }
        return "bin";
    }
}
