package com.example.ragdemo.infrastructure.marker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.infrastructure.marker.exception.MarkerException;

/**
 * Marker API 真实连通性集成测试（skill §39 Phase A 验收：真实 Java → SSH Tunnel → Marker API）。
 * <p>
 * 依赖本地 SSH Tunnel（127.0.0.1:18001）。Tunnel 未开启时自动跳过（Assumptions.abort），
 * 因此不会阻塞无环境的 CI。
 */
class MarkerApiIntegrationTest {

    private static final String BASE_URL = "http://127.0.0.1:18001";

    private MarkerHttpClient realClient() {
        MarkerProperties p = new MarkerProperties();
        p.setBaseUrl(BASE_URL);
        p.setConnectTimeout(Duration.ofSeconds(5));
        p.setReadTimeout(Duration.ofSeconds(30));
        return new MarkerHttpClient(p);
    }

    @Test
    void health_realMarkerApi_isOk() {
        MarkerHttpClient client = realClient();
        MarkerHealth health;
        try {
            health = client.health();
        } catch (MarkerException e) {
            Assumptions.abort("Marker API 不可达（SSH Tunnel 未开启），跳过集成测试: " + e.getMessage());
            return;
        }
        assertThat(health.status()).isEqualTo("ok");
    }

    @Test
    void isAlive_realMarkerApi_isTrue() {
        MarkerHttpClient client = realClient();
        if (!client.isAlive()) {
            Assumptions.abort("Marker API 不可达（SSH Tunnel 未开启），跳过集成测试");
        }
        assertThat(client.isAlive()).isTrue();
    }
}
