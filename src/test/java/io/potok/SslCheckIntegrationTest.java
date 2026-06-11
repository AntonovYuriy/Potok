package io.potok;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** ssl_check against a real TLS handshake (WireMock's self-signed HTTPS cert). */
class SslCheckIntegrationTest extends IntegrationTestBase {

    static final WireMockServer TLS = new WireMockServer(
            WireMockConfiguration.options().dynamicPort().dynamicHttpsPort());

    @BeforeAll
    static void startTls() {
        TLS.start();
    }

    @AfterAll
    static void stopTls() {
        TLS.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsCertificateFromLiveTlsEndpoint() {
        postYaml("/api/workflows", """
                name: ssl-check-test
                trigger:
                  webhook: { path: "ssl-check-test" }
                steps:
                  - name: check
                    action: ssl_check
                    with: { host: "localhost", port: "%d" }
                """.formatted(TLS.httpsPort()));

        String executionId = (String) postJson("/hooks/ssl-check-test", Map.of())
                .getBody().get("executionId");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) getExecution(executionId).get("steps");
        Map<String, Object> output = (Map<String, Object>) steps.get(0).get("output");
        assertThat(output.get("host")).isEqualTo("localhost");
        assertThat((Number) output.get("days_left")).isNotNull();
        assertThat((String) output.get("not_after")).isNotBlank();
        assertThat((String) output.get("subject")).isNotBlank();
    }
}
