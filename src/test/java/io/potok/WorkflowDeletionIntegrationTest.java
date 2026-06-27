package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
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

/**
 * Permanent workflow deletion: refused while enabled, wipes the workflow and
 * all its history once disabled. The default (no-param) DELETE still soft-disables.
 */
class WorkflowDeletionIntegrationTest extends IntegrationTestBase {

    private String yaml(String name) {
        return """
                name: %s
                trigger:
                  webhook: { path: "%s" }
                steps:
                  - name: fetch
                    action: http
                    with:
                      method: GET
                      url: "%s/data"
                """.formatted(name, name, WIRE_MOCK.baseUrl());
    }

    private String create(String name) {
        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", yaml(name));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    private ResponseEntity<Void> delete(String id, boolean permanent) {
        return rest.exchange("/api/workflows/" + id + (permanent ? "?permanent=true" : ""),
                HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
    }

    private HttpStatus statusOf(String id) {
        return (HttpStatus) rest.getForEntity("/api/workflows/" + id, Map.class).getStatusCode();
    }

    @Test
    void permanentDeleteRefusedWhileEnabled() {
        String id = create("del-enabled");

        ResponseEntity<Void> response = delete(id, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(id)).isEqualTo(HttpStatus.OK); // still there
    }

    @Test
    @SuppressWarnings("unchecked")
    void permanentDeleteWipesWorkflowAndHistory() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/data"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        String id = create("del-history");

        // produce an execution (+ step rows + a version) so the cascade is exercised
        String executionId = (String) postJson("/hooks/del-history", Map.of()).getBody().get("executionId");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        // must disable first
        assertThat(delete(id, false).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // now permanent delete succeeds despite executions/versions referencing it
        assertThat(delete(id, true).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(statusOf(id)).isEqualTo(HttpStatus.NOT_FOUND);
        List<Map<String, Object>> all = rest.getForObject("/api/workflows", List.class);
        assertThat(all).noneMatch(w -> id.equals(w.get("id")));
    }

    @Test
    void softDeleteStillDisables() {
        String id = create("del-soft");

        assertThat(delete(id, false).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map<String, Object>> got = rest.exchange(
                "/api/workflows/" + id, HttpMethod.GET, HttpEntity.EMPTY, MAP_TYPE);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(got.getBody().get("enabled")).isEqualTo(false);
    }

    @Test
    void permanentDeleteUnknownIsNotFound() {
        assertThat(delete("00000000-0000-0000-0000-000000000000", true).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
