package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorsIntegrationTest extends IntegrationTestBase {

    @Test
    void invalidYamlReturnsProblemJson() {
        ResponseEntity<Map<String, Object>> response = postYaml("/api/workflows", """
                name: broken
                trigger:
                  cron: "0 19 * * *"
                  webhook: { path: "x" }
                steps:
                  - { name: a, action: http }
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat((String) response.getBody().get("detail")).contains("exactly one");
    }

    @Test
    void duplicateNameReturnsConflict() {
        String yaml = """
                name: duped
                trigger:
                  webhook: { path: "duped" }
                steps:
                  - { name: a, action: http, with: { url: "https://example.com" } }
                """;
        assertThat(postYaml("/api/workflows", yaml).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> second = postYaml("/api/workflows", yaml);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) second.getBody().get("detail")).contains("duped");
    }

    @Test
    void unknownWebhookPathReturns404() {
        ResponseEntity<Map<String, Object>> response = postJson("/hooks/nope", Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownExecutionReturns404() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/api/executions/00000000-0000-0000-0000-000000000000",
                org.springframework.http.HttpMethod.GET, null, MAP_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteDisablesWorkflowAndWebhookStopsMatching() {
        var created = postYaml("/api/workflows", """
                name: disable-me
                trigger:
                  webhook: { path: "disable-me" }
                steps:
                  - { name: a, action: http, with: { url: "https://example.com" } }
                """);
        String id = (String) created.getBody().get("id");

        rest.delete("/api/workflows/" + id);

        Map<String, Object> after = rest.getForObject("/api/workflows/" + id, Map.class);
        assertThat(after.get("enabled")).isEqualTo(false);
        assertThat(postJson("/hooks/disable-me", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
