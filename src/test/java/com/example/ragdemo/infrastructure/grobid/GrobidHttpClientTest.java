package com.example.ragdemo.infrastructure.grobid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import com.example.ragdemo.infrastructure.grobid.exception.GrobidException;
import com.example.ragdemo.infrastructure.grobid.exception.GrobidInvalidResponseException;
import com.example.ragdemo.infrastructure.grobid.exception.GrobidProcessingException;
import com.example.ragdemo.infrastructure.grobid.exception.GrobidTimeoutException;
import com.example.ragdemo.infrastructure.grobid.exception.GrobidUnavailableException;

/**
 * GrobidHttpClient 单元测试：不依赖真实 GROBID 容器，
 * 覆盖 skill 要求的 HTTP 状态/错误映射表。
 */
class GrobidHttpClientTest {

    private static final String BASE_URL = "http://localhost:8070";
    private static final String TEI_XML = "<?xml version=\"1.0\"?><TEI xmlns=\"http://www.tei-c.org/ns/1.0\"></TEI>";

    private MockRestServiceServer server;
    private GrobidHttpClient client;

    private GrobidProperties properties() {
        GrobidProperties p = new GrobidProperties();
        p.setBaseUrl(BASE_URL);
        p.setConnectTimeout(Duration.ofSeconds(3));
        p.setReadTimeout(Duration.ofSeconds(30));
        p.setMaxRetries(0); // 测试中默认不重试，避免 sleep
        p.setRetryBackoff(Duration.ofMillis(1));
        return p;
    }

    private GrobidHttpClient newClient(GrobidProperties p) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        return new GrobidHttpClient(p, builder.build(), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @BeforeEach
    void setUp() {
        client = newClient(properties());
    }

    private void expectFulltextResponse(int status, String body) {
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/api/processFulltextDocument"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatusCode.valueOf(status))
                        .contentType(MediaType.APPLICATION_XML)
                        .body(body == null ? "" : body));
    }

    // ==================== 成功路径 ====================

    @Test
    void processFulltext_200_validXml_returnsTeiXml() {
        expectFulltextResponse(200, TEI_XML);

        GrobidResponse response = client.processFulltext(new byte[]{1, 2, 3}, "paper.pdf");

        assertThat(response.teiXml()).contains("<TEI");
        assertThat(response.durationMs()).isGreaterThanOrEqualTo(0);
        server.verify();
    }

    // ==================== 无效响应 ====================

    @Test
    void processFulltext_200_emptyBody_throwsInvalidResponse() {
        expectFulltextResponse(200, "");

        assertThatThrownBy(() -> client.processFulltext(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(GrobidInvalidResponseException.class)
                .satisfies(e -> assertThat(((GrobidException) e).isRetryable()).isFalse());
    }

    @Test
    void processFulltext_204_noContent_throwsInvalidResponse() {
        expectFulltextResponse(204, "");

        assertThatThrownBy(() -> client.processFulltext(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(GrobidInvalidResponseException.class)
                .satisfies(e -> assertThat(((GrobidException) e).isRetryable()).isFalse());
    }

    // ==================== 400 ====================

    @Test
    void processFulltext_400_throwsNonRetryableProcessing() {
        expectFulltextResponse(400, "invalid input");

        assertThatThrownBy(() -> client.processFulltext(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(GrobidProcessingException.class)
                .satisfies(e -> {
                    GrobidException ex = (GrobidException) e;
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.getHttpStatus()).isEqualTo(400);
                });
    }

    // ==================== 503 ====================

    @Test
    void processFulltext_503_throwsRetryableUnavailable() {
        expectFulltextResponse(503, "engine pool exhausted");

        assertThatThrownBy(() -> client.processFulltext(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(GrobidUnavailableException.class)
                .satisfies(e -> {
                    GrobidException ex = (GrobidException) e;
                    assertThat(ex.isRetryable()).isTrue();
                    assertThat(ex.getHttpStatus()).isEqualTo(503);
                });
    }

    // ==================== 500 错误码分类 ====================

    @Test
    void processFulltext_500_badInputData_throwsNonRetryable() {
        expectFulltextResponse(500, "{\"error\": \"BAD_INPUT_DATA\"}");

        assertThatThrownBy(() -> client.processFulltext(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(GrobidProcessingException.class)
                .satisfies(e -> {
                    GrobidException ex = (GrobidException) e;
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.getGrobidErrorCode()).isEqualTo("BAD_INPUT_DATA");
                });
    }

    @Test
    void processFulltext_500_timeout_throwsRetryableTimeout() {
        expectFulltextResponse(500, "TIMEOUT");

        assertThatThrownBy(() -> client.processFulltext(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(GrobidTimeoutException.class)
                .satisfies(e -> {
                    GrobidException ex = (GrobidException) e;
                    assertThat(ex.isRetryable()).isTrue();
                    assertThat(ex.getGrobidErrorCode()).isEqualTo("TIMEOUT");
                });
    }

    @Test
    void processFulltext_500_general_throwsRetryable() {
        expectFulltextResponse(500, "GENERAL error");

        assertThatThrownBy(() -> client.processFulltext(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(GrobidProcessingException.class)
                .satisfies(e -> assertThat(((GrobidException) e).isRetryable()).isTrue());
    }

    // ==================== 重试逻辑 ====================

    @Test
    void processFulltext_503Then200_retriesAndSucceeds() {
        GrobidProperties p = properties();
        p.setMaxRetries(1);
        p.setRetryBackoff(Duration.ofMillis(1));
        client = newClient(p);

        // 第一次 503（可重试），第二次 200
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/api/processFulltextDocument"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatusCode.valueOf(503)));
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/api/processFulltextDocument"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatusCode.valueOf(200))
                        .contentType(MediaType.APPLICATION_XML)
                        .body(TEI_XML));

        GrobidResponse response = client.processFulltext(new byte[]{1}, "paper.pdf");

        assertThat(response.teiXml()).contains("<TEI");
        server.verify();
    }

    @Test
    void processFulltext_503RetriesExhausted_throwsUnavailable() {
        GrobidProperties p = properties();
        p.setMaxRetries(1);
        p.setRetryBackoff(Duration.ofMillis(1));
        client = newClient(p);

        // maxRetries=1 → 共 2 次尝试，全部 503
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/api/processFulltextDocument"))
                .andRespond(MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatusCode.valueOf(503)));
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/api/processFulltextDocument"))
                .andRespond(MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatusCode.valueOf(503)));

        assertThatThrownBy(() -> client.processFulltext(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(GrobidUnavailableException.class);
        server.verify();
    }

    // ==================== 不可重试不重试 ====================

    @Test
    void processFulltext_400_nonRetryable_doesNotRetry() {
        // 只注册一个 400 响应：若客户端重试会导致第二个 expectation 缺失而报错
        expectFulltextResponse(400, "bad");

        assertThatThrownBy(() -> client.processFulltext(new byte[]{1}, "paper.pdf"))
                .isInstanceOf(GrobidProcessingException.class);
        server.verify();
    }

    // ==================== 输入校验 ====================

    @Test
    void processFulltext_emptyPdf_throwsIllegalArgument() {
        assertThatThrownBy(() -> client.processFulltext(new byte[]{}, "paper.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processFulltext_nonPdfName_throwsIllegalArgument() {
        assertThatThrownBy(() -> client.processFulltext(new byte[]{1}, "paper.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== isAlive / health ====================

    @Test
    void isAlive_200_returnsTrue() {
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/api/isalive"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess());

        assertThat(client.isAlive()).isTrue();
        server.verify();
    }

    @Test
    void isAlive_503_returnsFalse() {
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/api/isalive"))
                .andRespond(MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatusCode.valueOf(503)));

        assertThat(client.isAlive()).isFalse();
        server.verify();
    }

    @Test
    void health_200_parsesJson() {
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/api/health"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"active\":2,\"idle\":0,\"max\":3,\"extraField\":\"ignored\"}"));

        GrobidHealth health = client.health();

        assertThat(health.active()).isEqualTo(2);
        assertThat(health.idle()).isEqualTo(0);
        assertThat(health.max()).isEqualTo(3);
        server.verify();
    }

    @Test
    void health_503_throwsUnavailable() {
        server.expect(MockRestRequestMatchers.requestTo(BASE_URL + "/api/health"))
                .andRespond(MockRestResponseCreators.withStatus(
                        org.springframework.http.HttpStatusCode.valueOf(503)));

        assertThatThrownBy(() -> client.health())
                .isInstanceOf(GrobidUnavailableException.class);
    }
}
