package com.example.ragdemo.infrastructure.marker;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Marker API /api/v1/parse 响应。
 * <p>
 * 刻意不把 {@code data} 反序列化为 AcademicDocument：
 * <pre>
 * Marker API Schema != Domain Schema（必须解耦）
 * </pre>
 * data 保留为原始 JsonNode，交由 {@code MarkerDocumentParser} 做结构映射。
 *
 * @param success        是否成功
 * @param filename       原始文件名
 * @param elapsedSeconds Marker 解析耗时（秒）
 * @param data           原始 Marker Document Tree JSON
 * @param errorCode      失败时的错误码（如 MARKER_PARSE_FAILED）
 * @param message        失败时的错误信息
 */
public record MarkerResponse(
        boolean success,
        String filename,
        Double elapsedSeconds,
        JsonNode data,
        String errorCode,
        String message
) {
}
