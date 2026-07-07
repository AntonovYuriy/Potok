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

    @Test
    void unknownActionTypeIsRejectedAtCreate() {
        // M13: "telegran" used to create fine and fail only at run time (bypassing the DLQ)
        var response = postYaml("/api/workflows", """
                name: typo-action
                trigger:
                  webhook: { path: "typo-action" }
                steps:
                  - { name: notify, action: telegran, with: { chat_id: "1", text: "hi" } }
                """);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("detail"))
                .contains("unknown action 'telegran'")
                .contains("telegram"); // the available list helps fix the typo
    }

    @Test
    void runOfDisabledWorkflowIsRejected() {
        var created = postYaml("/api/workflows", """
                name: disabled-run
                trigger:
                  webhook: { path: "disabled-run" }
                steps:
                  - { name: n, action: http, with: { url: "http://example.invalid", fail_on_status: false } }
                """);
        String id = (String) created.getBody().get("id");
        rest.delete("/api/workflows/" + id); // soft-disable

        // M13: manual run now respects enabled like every other trigger source
        var run = postJson("/api/workflows/" + id + "/run", java.util.Map.of());
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) run.getBody().get("detail")).contains("disabled");

        // re-enabled -> runs again
        postJson("/api/workflows/" + id + "/enable", java.util.Map.of());
        assertThat(postJson("/api/workflows/" + id + "/run", java.util.Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }
}
