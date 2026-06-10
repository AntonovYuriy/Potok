package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Exhausted retries land in the DLQ; requeue puts the job back and the execution finishes. */
class DlqIntegrationTest extends IntegrationTestBase {

    @Test
    @SuppressWarnings("unchecked")
    void exhaustedJobLandsInDlqAndSucceedsAfterRequeue() {
        // fail twice is irrelevant — stub starts broken, gets fixed before requeue
        WIRE_MOCK.stubFor(get(urlEqualTo("/flaky"))
                .willReturn(aResponse().withStatus(500).withBody("broken")));

        postYaml("/api/workflows", """
                name: dlq-flow
                trigger:
                  webhook: { path: "dlq-flow" }
                steps:
                  - name: fetch
                    action: http
                    retry: { max_attempts: 2, base_delay: 50ms, max_delay: 100ms }
                    with: { method: GET, url: "%s/flaky" }
                """.formatted(WIRE_MOCK.baseUrl()));

        String executionId = (String) postJson("/hooks/dlq-flow", Map.of("k", "v")).getBody().get("executionId");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("FAILED"));

        // the job is in the DLQ with full context
        Map<String, Object> dlqPage = rest.getForObject("/api/dlq", Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) dlqPage.get("items");
        Map<String, Object> entry = items.stream()
                .filter(i -> executionId.equals(i.get("executionId")))
                .findFirst()
                .orElseThrow();
        assertThat(entry.get("stepName")).isEqualTo("fetch");
        assertThat(entry.get("attempts")).isEqualTo(2);
        assertThat((String) entry.get("lastError")).contains("500");
        Map<String, Object> payload = (Map<String, Object>) entry.get("payload");
        assertThat((Map<String, Object>) payload.get("input")).containsEntry("method", "GET");
        assertThat(((Map<String, Object>) ((Map<String, Object>) payload.get("trigger_info")).get("payload")))
                .containsEntry("k", "v");

        // fix the upstream, requeue, expect success
        WIRE_MOCK.stubFor(get(urlEqualTo("/flaky"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\": true}")));

        Number dlqId = (Number) entry.get("id");
        ResponseEntity<Map<String, Object>> requeued =
                postJson("/api/dlq/" + dlqId + "/requeue", Map.of());
        assertThat(requeued.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        // DLQ entry is gone
        Map<String, Object> after = rest.getForObject("/api/dlq", Map.class);
        assertThat((List<?>) after.get("items"))
                .noneMatch(i -> dlqId.equals(((Map<String, Object>) i).get("id")));
    }

    @Test
    void deleteRemovesDlqEntry() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/dead"))
                .willReturn(aResponse().withStatus(500)));

        postYaml("/api/workflows", """
                name: dlq-delete
                trigger:
                  webhook: { path: "dlq-delete" }
                steps:
                  - name: fetch
                    action: http
                    retry: { max_attempts: 1 }
                    with: { method: GET, url: "%s/dead" }
                """.formatted(WIRE_MOCK.baseUrl()));

        String executionId = (String) postJson("/hooks/dlq-delete", Map.of()).getBody().get("executionId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("FAILED"));

        Map<String, Object> page = rest.getForObject("/api/dlq", Map.class);
        @SuppressWarnings("unchecked")
        Number id = ((List<Map<String, Object>>) page.get("items")).stream()
                .filter(i -> executionId.equals(i.get("executionId")))
                .map(i -> (Number) i.get("id"))
                .findFirst()
                .orElseThrow();

        rest.delete("/api/dlq/" + id);

        ResponseEntity<Map<String, Object>> gone = postJson("/api/dlq/" + id + "/requeue", Map.of());
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
