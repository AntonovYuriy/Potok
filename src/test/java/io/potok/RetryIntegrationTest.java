package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** A persistently failing step is retried max_attempts times, then step and execution go FAILED. */
class RetryIntegrationTest extends IntegrationTestBase {

    @Test
    @SuppressWarnings("unchecked")
    void failingStepRetriesThenExecutionFails() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/broken"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", """
                name: retry-then-fail
                trigger:
                  webhook: { path: "retry" }
                steps:
                  - name: fetch
                    action: http
                    max_attempts: 3
                    with: { method: GET, url: "%s/broken" }
                  - name: never
                    action: telegram
                    with: { chat_id: "42", text: "unreachable" }
                """.formatted(WIRE_MOCK.baseUrl()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String executionId = (String) postJson("/hooks/retry", Map.of()).getBody().get("executionId");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("FAILED"));

        // exactly max_attempts requests — no more retries after exhaustion
        WIRE_MOCK.verify(3, getRequestedFor(urlEqualTo("/broken")));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) getExecution(executionId).get("steps");
        assertThat(steps).hasSize(1);
        Map<String, Object> fetch = steps.get(0);
        assertThat(fetch.get("status")).isEqualTo("FAILED");
        assertThat(fetch.get("attempt")).isEqualTo(3);
        assertThat((String) fetch.get("error")).contains("500");
    }
}
