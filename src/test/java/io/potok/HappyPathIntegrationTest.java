package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end happy path: workflow created via API, triggered via webhook,
 * http step hits WireMock, telegram step hits a WireMock stub of the
 * Bot API, execution SUCCEEDED with step outputs persisted.
 */
class HappyPathIntegrationTest extends IntegrationTestBase {

    @Test
    @SuppressWarnings("unchecked")
    void webhookTriggeredWorkflowRunsToSuccess() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/data")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"garbage day tomorrow\"}")));
        WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlEqualTo("/bottest-token/sendMessage"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\": true}")));

        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", """
                name: happy-path
                trigger:
                  webhook: { path: "happy" }
                steps:
                  - name: fetch
                    action: http
                    with:
                      method: GET
                      url: "%s/data"
                  - name: notify
                    if: "{{ steps.fetch.status == 200 }}"
                    action: telegram
                    with:
                      chat_id: "42"
                      text: "Reminder: {{ steps.fetch.body.message }}"
                """.formatted(WIRE_MOCK.baseUrl()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> triggered =
                postJson("/hooks/happy", Map.of("source", "test"));
        assertThat(triggered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String executionId = (String) triggered.getBody().get("executionId");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        Map<String, Object> execution = getExecution(executionId);
        Map<String, Object> triggerInfo = (Map<String, Object>) execution.get("triggerInfo");
        assertThat(triggerInfo.get("type")).isEqualTo("webhook");
        assertThat((Map<String, Object>) triggerInfo.get("payload")).containsEntry("source", "test");

        List<Map<String, Object>> steps = (List<Map<String, Object>>) execution.get("steps");
        assertThat(steps).hasSize(2);

        Map<String, Object> fetch = steps.get(0);
        assertThat(fetch.get("name")).isEqualTo("fetch");
        assertThat(fetch.get("status")).isEqualTo("SUCCEEDED");
        Map<String, Object> fetchOutput = (Map<String, Object>) fetch.get("output");
        assertThat(fetchOutput.get("status")).isEqualTo(200);
        assertThat((Map<String, Object>) fetchOutput.get("body"))
                .containsEntry("message", "garbage day tomorrow");

        Map<String, Object> notify = steps.get(1);
        assertThat(notify.get("status")).isEqualTo("SUCCEEDED");
        assertThat((Map<String, Object>) notify.get("input"))
                .containsEntry("text", "Reminder: garbage day tomorrow");

        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo("/bottest-token/sendMessage"))
                .withRequestBody(containing("\"chat_id\":\"42\""))
                .withRequestBody(containing("Reminder: garbage day tomorrow")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failOnStatusFalseLetsConditionReactToErrorStatus() {
        // healthcheck.yaml pattern: probe a down service, alert on non-200
        WIRE_MOCK.stubFor(get(urlEqualTo("/down")).willReturn(aResponse().withStatus(503)));
        WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlEqualTo("/bottest-token/sendMessage"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\": true}")));

        postYaml("/api/workflows", """
                name: healthcheck-pattern
                trigger:
                  webhook: { path: "health" }
                steps:
                  - name: probe
                    action: http
                    with: { method: GET, url: "%s/down", fail_on_status: false }
                  - name: alert
                    if: "{{ steps.probe.status != 200 }}"
                    action: telegram
                    with: { chat_id: "42", text: "ALERT: status {{ steps.probe.status }}" }
                """.formatted(WIRE_MOCK.baseUrl()));

        String executionId = (String) postJson("/hooks/health", Map.of()).getBody().get("executionId");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo("/bottest-token/sendMessage"))
                .withRequestBody(containing("ALERT: status 503")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void falseConditionSkipsStepAndExecutionSucceeds() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse()
                .withStatus(200).withBody("{\"up\": true}")));

        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", """
                name: skip-path
                trigger:
                  webhook: { path: "skip" }
                steps:
                  - name: check
                    action: http
                    with: { method: GET, url: "%s/ok" }
                  - name: alert
                    if: "{{ steps.check.status == 500 }}"
                    action: telegram
                    with: { chat_id: "42", text: "down!" }
                """.formatted(WIRE_MOCK.baseUrl()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String executionId = (String) postJson("/hooks/skip", Map.of()).getBody().get("executionId");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) getExecution(executionId).get("steps");
        assertThat(steps).extracting(s -> s.get("name"), s -> s.get("status"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("check", "SUCCEEDED"),
                        org.assertj.core.groups.Tuple.tuple("alert", "SKIPPED"));

        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/bottest-token/sendMessage")));
    }
}
