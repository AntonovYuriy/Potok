package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Dashboard is served from the jar; meta endpoint bootstraps it. */
class UiSmokeIntegrationTest extends IntegrationTestBase {

    @Test
    void staticDashboardLoads() {
        ResponseEntity<String> index = rest.getForEntity("/", String.class);
        assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(index.getHeaders().getContentType().toString()).contains("text/html");
        assertThat(index.getBody()).contains("Potok").contains("js/app.js");

        assertThat(rest.getForEntity("/js/app.js", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/css/app.css", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void metaWorks() {
        Map<String, Object> meta = rest.getForObject("/api/meta", Map.class);
        assertThat(meta).containsKeys("app", "authRequired");
    }

    @Test
    void enableEndpointWorks() {
        var created = postYaml("/api/workflows", """
                name: ui-enable-test
                trigger:
                  webhook: { path: "ui-enable" }
                steps:
                  - { name: a, action: http, with: { url: "https://example.com" } }
                """);
        String id = (String) created.getBody().get("id");

        rest.delete("/api/workflows/" + id);
        assertThat(rest.getForObject("/api/workflows/" + id, Map.class).get("enabled")).isEqualTo(false);

        ResponseEntity<Map<String, Object>> enabled =
                postJson("/api/workflows/" + id + "/enable", Map.of());
        assertThat(enabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enabled.getBody().get("enabled")).isEqualTo(true);
    }
}
