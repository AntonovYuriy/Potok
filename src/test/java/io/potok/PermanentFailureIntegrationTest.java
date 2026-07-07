package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M13: a permanent config error (missing 'url') used to throw out of the
 * handler and burn the whole retry budget before dead-lettering. It now fails
 * on the FIRST attempt with a readable reason and lands in the DLQ immediately.
 */
class PermanentFailureIntegrationTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    @Test
    void missingUrlDeadLettersOnFirstAttemptWithReadableError() {
        var created = postYaml("/api/workflows", """
                name: perm-fail
                trigger:
                  webhook: { path: "perm-fail" }
                steps:
                  - name: fetch
                    action: http
                    retry: { max_attempts: 5, base_delay: 50ms, max_delay: 100ms }
                    with: { method: GET }
                """);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String executionId = (String) postJson("/hooks/perm-fail", Map.of()).getBody().get("executionId");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("FAILED"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) getExecution(executionId).get("steps");
        Map<String, Object> fetch = steps.get(0);
        // exactly ONE attempt despite max_attempts: 5 — permanent failures skip the budget
        assertThat(fetch.get("attempt")).isEqualTo(1);
        assertThat((String) fetch.get("error")).contains("url");

        long dlq = jdbc.sql("select count(*) from dead_letter dl "
                        + "join workflow_execution we on we.id = dl.execution_id "
                        + "where we.id = :id::uuid")
                .param("id", executionId)
                .query(Long.class)
                .single();
        assertThat(dlq).as("dead-lettered immediately").isEqualTo(1);
    }
}
