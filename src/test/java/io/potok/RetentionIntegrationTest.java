package io.potok;

import io.potok.execution.RetentionPurger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Purge removes old finished executions, keeps recent ones and DLQ-referenced ones. */
class RetentionIntegrationTest extends IntegrationTestBase {

    @Autowired
    RetentionPurger purger;
    @Autowired
    JdbcClient jdbc;

    private String runToCompletion(String name, String path, boolean fail) {
        WIRE_MOCK.stubFor(get(urlEqualTo("/" + path))
                .willReturn(aResponse().withStatus(fail ? 500 : 200).withBody("{}")));
        var created = postYaml("/api/workflows", """
                name: %s
                trigger:
                  webhook: { path: "%s" }
                steps:
                  - name: fetch
                    action: http
                    retry: { max_attempts: 1 }
                    with: { method: GET, url: "%s/%s" }
                """.formatted(name, path, WIRE_MOCK.baseUrl(), path));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String executionId = (String) postJson("/hooks/" + path, Map.of()).getBody().get("executionId");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status"))
                        .isIn("SUCCEEDED", "FAILED"));
        return executionId;
    }

    private void ageExecution(String executionId, int days) {
        jdbc.sql("update workflow_execution set finished_at = now() - make_interval(days => :days) where id = :id::uuid")
                .param("days", days)
                .param("id", executionId)
                .update();
    }

    private long executionExists(String executionId) {
        return jdbc.sql("select count(*) from workflow_execution where id = :id::uuid")
                .param("id", executionId)
                .query(Long.class)
                .single();
    }

    @Test
    void purgeRemovesOldKeepsRecentAndDlqReferenced() {
        String oldDone = runToCompletion("ret-old", "ret-old", false);
        String recent = runToCompletion("ret-recent", "ret-recent", false);
        String oldDead = runToCompletion("ret-dead", "ret-dead", true); // lands in DLQ

        ageExecution(oldDone, 45);
        ageExecution(oldDead, 45);
        // recent stays at now()

        int purged = purger.purge();

        assertThat(purged).isEqualTo(1);
        assertThat(executionExists(oldDone)).isZero();          // old + finished -> gone
        assertThat(executionExists(recent)).isEqualTo(1);       // inside retention window
        assertThat(executionExists(oldDead)).isEqualTo(1);      // DLQ-referenced -> kept

        // default retention window: cutoff 30 days back
        assertThat(purger.cutoff(java.time.OffsetDateTime.now()))
                .isBefore(java.time.OffsetDateTime.now().minusDays(29));
    }
}
