package com.example.ragdemo.infrastructure.marker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Marker /health 响应。
 *
 * @param status 服务状态，如 "ok"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarkerHealth(
        String status
) {
}
