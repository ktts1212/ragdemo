package com.example.ragdemo.infrastructure.marker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import com.example.ragdemo.infrastructure.marker.exception.MarkerException;
import com.example.ragdemo.infrastructure.marker.exception.MarkerInvalidResponseException;
import com.example.ragdemo.infrastructure.marker.exception.MarkerProcessingException;
import com.example.ragdemo.infrastructure.marker.exception.MarkerUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MarkerHttpClient 单元测试（skill §31 Layer 1）：不依赖真实 Marker 容器。
 */
class MarkerHttpClientTest {

    private static final String BASE_URL = "http://127.0.0.1:18001";
    private static final String PARSE_SUCCESS = """
            {"success":true,"filename":"paper.pdf","elapsedSeconds":61.2,
             "data":{"block_type":"Document","children":[]}}
            """;

    private MockRestServiceServer server;
    private MarkerHttpClient client;

    private MarkerProperties properties() {
        MarkerProperties p = new MarkerProperties();
        p.setBaseUrl(BASE_URL);
        p.setConnectTimeout(Duration.ofSeconds(5));
        p.setReadTimeout(Duration.ofSeconds(30));
        return p;
    }

    private MarkerHttpClient newClient(MarkerProperties p) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        return new MarkerHttpClient(p, builder.build(), new ObjectMapper());
    }

    @BeforeEach
    void setUp() {
        client = newClient(properties());
    }

    private void expectParse(int status, String body) {
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/api/v1/parse"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatusCode.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body == null ? "" : body));
    }

    // ==================== 成功路径 ====================

    @Test
    void parse_200_success_returnsResponse() {
        expectParse(200, PARSE_SUCCESS);

        MarkerResponse response = client.parse(new byte[]{1, 2, 3}, "paper.pdf");

        assertThat(response.success()).isTrue();
        assertThat(response.filename()).isEqualTo("paper.pdf");
        assertThat(response.elapsedSeconds()).isEqualTo(61.2);
        assertThat(response.data()).isNotNull();
        assertThat(response.data().path("block_type").asText()).isEqualTo("Document");
        server.verify();
    }

    // ==================== 业务失败 / 无效响应 ====================

    @Test
    void parse_200_successFalse_throwsProcessingWithErrorCode() {
        expectParse(200, """
                {"success":false,"errorCode":"MARKER_PARSE_FAILED","message":"cannot parse"}
                """);

        assertThatThrownBy(() -> client.parse(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(MarkerProcessingException.class)
                .satisfies(e -> {
                    MarkerException ex = (MarkerException) e;
                    assertThat(ex.getErrorCode()).isEqualTo("MARKER_PARSE_FAILED");
                    assertThat(ex.isRetryable()).isFalse();
                });
    }

    @Test
    void parse_200_missingData_throwsInvalidResponse() {
        expectParse(200, "{\"success\":true,\"filename\":\"paper.pdf\"}");

        assertThatThrownBy(() -> client.parse(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(MarkerInvalidResponseException.class);
    }

    @Test
    void parse_200_invalidJson_throwsInvalidResponse() {
        expectParse(200, "not-a-json");

        assertThatThrownBy(() -> client.parse(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(MarkerInvalidResponseException.class);
    }

    @Test
    void parse_200_emptyBody_throwsInvalidResponse() {
        expectParse(200, "");

        assertThatThrownBy(() -> client.parse(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(MarkerInvalidResponseException.class);
    }

    // ==================== HTTP 错误 ====================

    @Test
    void parse_400_throwsProcessing() {
        expectParse(400, "bad file");

        assertThatThrownBy(() -> client.parse(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(MarkerProcessingException.class)
                .satisfies(e -> assertThat(((MarkerException) e).isRetryable()).isFalse());
    }

    @Test
    void parse_500_throwsRetryableUnavailable() {
        expectParse(500, "internal error");

        assertThatThrownBy(() -> client.parse(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(MarkerUnavailableException.class)
                .satisfies(e -> assertThat(((MarkerException) e).isRetryable()).isTrue());
    }

    // ==================== 输入校验 ====================

    @Test
    void parse_emptyPdf_throwsIllegalArgument() {
        assertThatThrownBy(() -> client.parse(new byte[]{}, "paper.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_blankFileName_throwsIllegalArgument() {
        assertThatThrownBy(() -> client.parse(new byte[]{1}, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== health / isAlive ====================

    @Test
    void health_200_parsesStatus() {
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/health"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"ok\"}"));

        MarkerHealth health = client.health();

        assertThat(health.status()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void health_503_throwsUnavailable() {
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/health"))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatusCode.valueOf(503)));

        assertThatThrownBy(() -> client.health())
                .isInstanceOf(MarkerUnavailableException.class);
    }

    @Test
    void isAlive_ok_returnsTrue() {
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/health"))
                .andRespond(MockRestResponseCreators.withSuccess()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"ok\"}"));

        assertThat(client.isAlive()).isTrue();
    }

    @Test
    void isAlive_unreachable_returnsFalse() {
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/health"))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatusCode.valueOf(503)));

        assertThat(client.isAlive()).isFalse();
    }
}
