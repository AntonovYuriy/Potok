package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M14 resource diet: quiet poll ticks must not write poll_state, conditional
 * GET must skip on 304, and rss dedupe must batch. All assertions go through
 * the DB row (last_polled_at moves only on a real write).
 */
class ResourceDietIntegrationTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> executionsOf(String workflowId) {
        return rest.getForObject("/api/executions?workflowId=" + workflowId, List.class);
    }

    private record StateRow(OffsetDateTime lastPolledAt, String etag) {
    }

    private StateRow stateRow(String workflowId) {
        return jdbc.sql("select last_polled_at, etag from poll_state where workflow_id = :id::uuid")
                .param("id", workflowId)
                .query((rs, n) -> new StateRow(
                        rs.getObject("last_polled_at", OffsetDateTime.class),
                        rs.getString("etag")))
                .optional().orElse(null);
    }

    private long rssSeenCount(String workflowId) {
        return jdbc.sql("select count(*) from rss_seen where workflow_id = :id::uuid")
                .param("id", workflowId).query(Long.class).single();
    }

    private String createPollWorkflow(String name, String path, String fireWhen) {
        var created = postYaml("/api/workflows", """
                name: %s
                trigger:
                  poll:
                    interval: 300ms
                    http: { method: GET, url: "%s%s" }
                    fire_when: "%s"
                steps:
                  - { name: noop, action: http, with: { url: "%s/ok", fail_on_status: false } }
                """.formatted(name, WIRE_MOCK.baseUrl(), path, fireWhen, WIRE_MOCK.baseUrl()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    @Test
    void unchangedTicksWriteNothingAfterBaseline() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(get(urlEqualTo("/quiet"))
                .willReturn(aResponse().withStatus(200).withBody("{\"v\": 1}")));

        String workflowId = createPollWorkflow("diet-quiet", "/quiet", "changed");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(stateRow(workflowId)).as("baseline writes the state row").isNotNull());
        OffsetDateTime baseline = stateRow(workflowId).lastPolledAt();

        Thread.sleep(1500); // ≥4 quiet ticks at 300ms
        assertThat(stateRow(workflowId).lastPolledAt())
                .as("identical ticks must not touch poll_state")
                .isEqualTo(baseline);
        assertThat(executionsOf(workflowId)).isEmpty();

        // a real change still writes (and fires)
        WIRE_MOCK.stubFor(get(urlEqualTo("/quiet"))
                .willReturn(aResponse().withStatus(200).withBody("{\"v\": 2}")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(executionsOf(workflowId)).hasSize(1);
            assertThat(stateRow(workflowId).lastPolledAt()).isAfter(baseline);
        });
    }

    @Test
    void conditionalGetSkipsBodyAndWritesOn304() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        // full response carries an ETag…
        WIRE_MOCK.stubFor(get(urlEqualTo("/etagged"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("ETag", "\"v1\"")
                        .withBody("{\"price\": 100}")));
        // …and a revalidation with that ETag is answered 304 with no body
        WIRE_MOCK.stubFor(get(urlEqualTo("/etagged"))
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse().withStatus(304)));

        var created = postYaml("/api/workflows", """
                name: diet-etag
                trigger:
                  poll:
                    interval: 300ms
                    http: { method: GET, url: "%s/etagged" }
                    extract: { jsonpath: "$.price" }
                    fire_when: "{{ poll.value > 150 }}"
                steps:
                  - { name: noop, action: http, with: { url: "%s/ok", fail_on_status: false } }
                """.formatted(WIRE_MOCK.baseUrl(), WIRE_MOCK.baseUrl()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workflowId = (String) created.getBody().get("id");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            StateRow row = stateRow(workflowId);
            assertThat(row).isNotNull();
            assertThat(row.etag()).as("baseline stores the validator").isEqualTo("\"v1\"");
        });
        OffsetDateTime baseline = stateRow(workflowId).lastPolledAt();

        Thread.sleep(1500); // every follow-up tick revalidates and gets 304
        WIRE_MOCK.verify(getRequestedFor(urlEqualTo("/etagged"))
                .withHeader("If-None-Match", equalTo("\"v1\"")));
        assertThat(stateRow(workflowId).lastPolledAt())
                .as("304 ticks must not write state").isEqualTo(baseline);
        assertThat(executionsOf(workflowId)).as("304 never fires").isEmpty();

        // content moves: revalidation now yields a fresh 200 + new ETag → edge fires;
        // v2 revalidations get 304 so the run stabilizes after exactly one fire
        WIRE_MOCK.stubFor(get(urlEqualTo("/etagged"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("ETag", "\"v2\"")
                        .withBody("{\"price\": 200}")));
        WIRE_MOCK.stubFor(get(urlEqualTo("/etagged"))
                .withHeader("If-None-Match", equalTo("\"v1\""))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("ETag", "\"v2\"")
                        .withBody("{\"price\": 200}")));
        WIRE_MOCK.stubFor(get(urlEqualTo("/etagged"))
                .withHeader("If-None-Match", equalTo("\"v2\""))
                .willReturn(aResponse().withStatus(304)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(executionsOf(workflowId)).hasSize(1);
            assertThat(stateRow(workflowId).etag()).isEqualTo("\"v2\"");
        });
    }

    private static String rssFeed(String... items) {
        StringBuilder entries = new StringBuilder();
        for (String item : items) {
            entries.append("<item><title>").append(item)
                    .append("</title><link>https://example.org/").append(item)
                    .append("</link><guid>").append(item).append("</guid></item>\n");
        }
        return """
                <?xml version="1.0"?><rss version="2.0"><channel>
                <title>t</title><link>https://example.org</link><description>d</description>
                %s</channel></rss>""".formatted(entries);
    }

    @Test
    void rssBatchesDedupeAndHonors304() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(get(urlEqualTo("/diet-feed"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withHeader("ETag", "\"r1\"")
                        .withBody(rssFeed("a", "b", "c"))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/diet-feed"))
                .withHeader("If-None-Match", equalTo("\"r1\""))
                .willReturn(aResponse().withStatus(304)));

        var created = postYaml("/api/workflows", """
                name: diet-rss
                trigger:
                  rss: { interval: 300ms, url: "%s/diet-feed" }
                steps:
                  - { name: noop, action: http, with: { url: "%s/ok", fail_on_status: false } }
                """.formatted(WIRE_MOCK.baseUrl(), WIRE_MOCK.baseUrl()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workflowId = (String) created.getBody().get("id");

        // baseline: all items seen in one batch, none fire, validator stored
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(rssSeenCount(workflowId)).isEqualTo(3);
            assertThat(stateRow(workflowId).etag()).isEqualTo("\"r1\"");
        });
        assertThat(executionsOf(workflowId)).isEmpty();
        OffsetDateTime baseline = stateRow(workflowId).lastPolledAt();

        Thread.sleep(1200); // 304 ticks: no parse, no writes
        assertThat(stateRow(workflowId).lastPolledAt()).isEqualTo(baseline);
        assertThat(rssSeenCount(workflowId)).isEqualTo(3);

        // two genuinely new items arrive in one poll → both fire from a single batch;
        // r2 revalidations get 304 so the run stabilizes after those two fires
        WIRE_MOCK.stubFor(get(urlEqualTo("/diet-feed"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withHeader("ETag", "\"r2\"")
                        .withBody(rssFeed("a", "b", "c", "d", "e"))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/diet-feed"))
                .withHeader("If-None-Match", equalTo("\"r1\""))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/rss+xml")
                        .withHeader("ETag", "\"r2\"")
                        .withBody(rssFeed("a", "b", "c", "d", "e"))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/diet-feed"))
                .withHeader("If-None-Match", equalTo("\"r2\""))
                .willReturn(aResponse().withStatus(304)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(executionsOf(workflowId)).hasSize(2);
            assertThat(rssSeenCount(workflowId)).isEqualTo(5);
            assertThat(stateRow(workflowId).etag()).isEqualTo("\"r2\"");
        });
    }
}
