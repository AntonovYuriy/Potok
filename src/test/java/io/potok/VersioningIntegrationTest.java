package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Version history is append-only; rollback appends; executions pin their version. */
class VersioningIntegrationTest extends IntegrationTestBase {

    private static String yaml(String path, String url) {
        return """
                name: ver-test
                trigger:
                  webhook: { path: "%s" }
                steps:
                  - { name: fetch, action: http, with: { method: GET, url: "%s", fail_on_status: false } }
                """.formatted(path, url);
    }

    private ResponseEntity<Map<String, Object>> putYaml(String url, String yaml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(yaml, headers), MAP_TYPE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void versionsAppendRollbackAppendsAndExecutionsPinVersion() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/v1")).willReturn(aResponse().withStatus(200).withBody("{\"v\":1}")));
        WIRE_MOCK.stubFor(get(urlEqualTo("/v2")).willReturn(aResponse().withStatus(200).withBody("{\"v\":2}")));

        // v1
        var created = postYaml("/api/workflows", yaml("ver-1", WIRE_MOCK.baseUrl() + "/v1"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");
        assertThat(created.getBody().get("currentVersion")).isEqualTo(1);

        // v2 (update changes webhook path + url)
        var updated = putYaml("/api/workflows/" + id, yaml("ver-2", WIRE_MOCK.baseUrl() + "/v2"));
        assertThat(updated.getBody().get("currentVersion")).isEqualTo(2);

        // execution on v2 pins version 2
        String exec2 = (String) postJson("/hooks/ver-2", Map.of()).getBody().get("executionId");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(exec2).get("status")).isEqualTo("SUCCEEDED"));
        assertThat(getExecution(exec2).get("versionNo")).isEqualTo(2);

        // history: 2 entries, newest first, raw yaml present
        Map<String, Object> versions = rest.getForObject("/api/workflows/" + id + "/versions", Map.class);
        assertThat(versions.get("total")).isEqualTo(2);
        List<Map<String, Object>> items = (List<Map<String, Object>>) versions.get("items");
        assertThat(items.get(0).get("versionNo")).isEqualTo(2);
        assertThat((String) items.get(1).get("yamlSource")).contains("ver-1");

        // rollback to v1 -> creates v3 with v1 content; history grows, never rewrites
        ResponseEntity<Map<String, Object>> rolledBack =
                postJson("/api/workflows/" + id + "/versions/1/rollback", Map.of());
        assertThat(rolledBack.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rolledBack.getBody().get("currentVersion")).isEqualTo(3);

        Map<String, Object> after = rest.getForObject("/api/workflows/" + id + "/versions", Map.class);
        assertThat(after.get("total")).isEqualTo(3);
        List<Map<String, Object>> afterItems = (List<Map<String, Object>>) after.get("items");
        assertThat(afterItems.get(0).get("versionNo")).isEqualTo(3);
        assertThat((String) afterItems.get(0).get("comment")).contains("rollback to v1");
        assertThat((String) afterItems.get(0).get("yamlSource")).contains("ver-1");

        // execution after rollback uses the rolled-back definition (old webhook path lives again)
        String exec3 = (String) postJson("/hooks/ver-1", Map.of()).getBody().get("executionId");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(exec3).get("status")).isEqualTo("SUCCEEDED"));
        assertThat(getExecution(exec3).get("versionNo")).isEqualTo(3);
        // and the old v2 path is gone
        assertThat(postJson("/hooks/ver-2", Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // rollback to unknown version -> 404
        assertThat(postJson("/api/workflows/" + id + "/versions/99/rollback", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
