package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Partial unique index: names are unique among ACTIVE workflows only. */
class NameReuseIntegrationTest extends IntegrationTestBase {

    private static final String YAML = """
            name: reusable-name
            trigger:
              webhook: { path: "reusable-%s" }
            steps:
              - { name: a, action: http, with: { url: "https://example.com" } }
            """;

    @Test
    void deletedWorkflowNameCanBeReused() {
        ResponseEntity<Map<String, Object>> first = postYaml("/api/workflows", YAML.formatted("one"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String firstId = (String) first.getBody().get("id");

        // active duplicate → 409
        ResponseEntity<Map<String, Object>> duplicate = postYaml("/api/workflows", YAML.formatted("two"));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // soft delete frees the name
        rest.delete("/api/workflows/" + firstId);
        ResponseEntity<Map<String, Object>> recreated = postYaml("/api/workflows", YAML.formatted("three"));
        assertThat(recreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String secondId = (String) recreated.getBody().get("id");
        assertThat(secondId).isNotEqualTo(firstId);

        // old workflow still exists, disabled, history intact
        Map<String, Object> old = rest.getForObject("/api/workflows/" + firstId, Map.class);
        assertThat(old.get("enabled")).isEqualTo(false);
        assertThat(old.get("name")).isEqualTo("reusable-name");
    }
}
