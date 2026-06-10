package io.potok;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** /actuator/prometheus exposes potok_* meters after a run; probes are up. */
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class MetricsIntegrationTest extends IntegrationTestBase {

    @Test
    void prometheusExposesPotokMetricsAfterARun() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/m")).willReturn(aResponse()
                .withStatus(200).withBody("{\"ok\": true}")));

        postYaml("/api/workflows", """
                name: metrics-run
                trigger:
                  webhook: { path: "metrics-run" }
                steps:
                  - { name: fetch, action: http, with: { method: GET, url: "%s/m" } }
                """.formatted(WIRE_MOCK.baseUrl()));

        String executionId = (String) postJson("/hooks/metrics-run", Map.of()).getBody().get("executionId");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        String metrics = rest.getForObject("/actuator/prometheus", String.class);
        assertThat(metrics)
                .contains("potok_executions_started_total")
                .contains("potok_executions_succeeded_total")
                .contains("potok_queue_depth")
                .contains("potok_dlq_size")
                .contains("potok_step_duration_seconds")
                .contains("action=\"http\"");
    }

    @Test
    void probesAreExposed() {
        Map<String, Object> liveness = rest.getForObject("/actuator/health/liveness", Map.class);
        assertThat(liveness.get("status")).isEqualTo("UP");

        Map<String, Object> readiness = rest.getForObject("/actuator/health/readiness", Map.class);
        assertThat(readiness.get("status")).isEqualTo("UP");
    }
}
