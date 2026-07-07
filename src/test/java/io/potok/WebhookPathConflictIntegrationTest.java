package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M13: two ENABLED workflows on one webhook path used to 500 every delivery
 * and silence both. The path is now unique among enabled workflows: create /
 * update / enable are rejected with 409, and deliveries keep working.
 */
class WebhookPathConflictIntegrationTest extends IntegrationTestBase {

    private static String hookWorkflow(String name, String path) {
        return """
                name: %s
                trigger:
                  webhook: { path: "%s" }
                steps:
                  - { name: n, action: http, with: { url: "http://example.invalid", fail_on_status: false } }
                """.formatted(name, path);
    }

    @Test
    void secondWorkflowOnSamePathIsRejectedAndDeliveryKeepsWorking() {
        ResponseEntity<Map<String, Object>> first =
                postYaml("/api/workflows", hookWorkflow("hookdup-a", "hookdup"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> second =
                postYaml("/api/workflows", hookWorkflow("hookdup-b", "hookdup"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) second.getBody().get("detail"))
                .contains("webhook path 'hookdup'");

        // delivery to the surviving workflow is a 202, not the old 500
        ResponseEntity<Map<String, Object>> fire = postJson("/hooks/hookdup", Map.of());
        assertThat(fire.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void pathFreesUpWhenTheHolderIsDisabled() {
        ResponseEntity<Map<String, Object>> first =
                postYaml("/api/workflows", hookWorkflow("hookfree-a", "hookfree"));
        String firstId = (String) first.getBody().get("id");

        rest.delete("/api/workflows/" + firstId); // soft-disable frees the path

        ResponseEntity<Map<String, Object>> second =
                postYaml("/api/workflows", hookWorkflow("hookfree-b", "hookfree"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // re-enabling the first would collide again -> 409
        ResponseEntity<Map<String, Object>> enable = postJson(
                "/api/workflows/" + firstId + "/enable", Map.of());
        assertThat(enable.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateOntoATakenPathIsRejected() {
        postYaml("/api/workflows", hookWorkflow("hookupd-a", "hookupd-taken"));
        ResponseEntity<Map<String, Object>> other =
                postYaml("/api/workflows", hookWorkflow("hookupd-b", "hookupd-own"));
        String otherId = (String) other.getBody().get("id");

        // moving B onto A's path must 409; B keeps its own path
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        ResponseEntity<Map<String, Object>> update = rest.exchange(
                "/api/workflows/" + otherId, org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        hookWorkflow("hookupd-b", "hookupd-taken"), headers),
                MAP_TYPE);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map<String, Object>> fire = postJson("/hooks/hookupd-own", Map.of());
        assertThat(fire.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
