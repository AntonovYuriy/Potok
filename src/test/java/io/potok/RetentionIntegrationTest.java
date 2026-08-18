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
    @Autowired
    io.potok.trigger.PollStateRepository pollState;

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

    private String createWorkflow(String name, String path) {
        var created = postYaml("/api/workflows", """
                name: %s
                trigger:
                  webhook: { path: "%s" }
                steps:
                  - { name: noop, action: http, with: { url: "http://example.invalid", fail_on_status: false } }
                """.formatted(name, path));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    private void insertRssSeen(String workflowId, String itemId, int ageDays) {
        jdbc.sql("""
                        insert into rss_seen (workflow_id, item_id, seen_at)
                        values (:id::uuid, :item, now() - make_interval(days => :days))
                        """)
                .param("id", workflowId).param("item", itemId).param("days", ageDays)
                .update();
    }

    private long rssSeenCount(String workflowId, String itemId) {
        return jdbc.sql("select count(*) from rss_seen where workflow_id = :id::uuid and item_id = :item")
                .param("id", workflowId).param("item", itemId).query(Long.class).single();
    }

    @Test
    void purgeRemovesOldRssSeenKeepsRecentDedupeStillWorks() {
        String workflowId = createWorkflow("ret-rss", "ret-rss");
        java.util.UUID id = java.util.UUID.fromString(workflowId);
        insertRssSeen(workflowId, "old-item", 45);    // well past the 30d window
        insertRssSeen(workflowId, "recent-item", 1);  // inside the window

        purger.purge();

        assertThat(rssSeenCount(workflowId, "old-item")).as("old dedupe row purged").isZero();
        assertThat(rssSeenCount(workflowId, "recent-item")).as("recent kept").isEqualTo(1);
        // dedupe still works: the kept item is still "seen" (batch returns only NEW ids)
        assertThat(pollState.markSeenBatch(id, java.util.List.of("recent-item")))
                .doesNotContain("recent-item");
    }
}
