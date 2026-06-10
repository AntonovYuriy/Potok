package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** poll/rss triggers against WireMock: change detection, edge-triggering, rss dedupe. */
class PollerIntegrationTest extends IntegrationTestBase {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> executionsOf(String workflowId) {
        return rest.getForObject("/api/executions?workflowId=" + workflowId, List.class);
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
    void changedModeFiresOnBodyChangeOnly() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(get(urlEqualTo("/watched"))
                .willReturn(aResponse().withStatus(200).withBody("{\"v\": 1}")));

        String workflowId = createPollWorkflow("poll-changed", "/watched", "changed");

        // baseline + repeat polls of the same body: no executions
        Thread.sleep(1500);
        assertThat(executionsOf(workflowId)).isEmpty();

        WIRE_MOCK.stubFor(get(urlEqualTo("/watched"))
                .willReturn(aResponse().withStatus(200).withBody("{\"v\": 2}")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(executionsOf(workflowId)).hasSize(1));

        // payload = the polled response
        Map<String, Object> execution = getExecution(
                (String) executionsOf(workflowId).get(0).get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> triggerInfo = (Map<String, Object>) execution.get("triggerInfo");
        assertThat(triggerInfo.get("type")).isEqualTo("poll");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) triggerInfo.get("payload");
        assertThat(((Map<String, Object>) payload.get("body"))).containsEntry("v", 2);

        // unchanged again: still exactly one
        Thread.sleep(1200);
        assertThat(executionsOf(workflowId)).hasSize(1);
    }

    @Test
    void expressionModeIsEdgeTriggered() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(get(urlEqualTo("/price"))
                .willReturn(aResponse().withStatus(200).withBody("{\"price\": 150}")));

        String workflowId = createPollWorkflow("poll-price", "/price", "{{ body.price < 100 }}");

        Thread.sleep(1200); // baseline: condition false, no fire
        assertThat(executionsOf(workflowId)).isEmpty();

        WIRE_MOCK.stubFor(get(urlEqualTo("/price"))
                .willReturn(aResponse().withStatus(200).withBody("{\"price\": 90}")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(executionsOf(workflowId)).hasSize(1));

        // stays true: no refire (edge, not level)
        WIRE_MOCK.stubFor(get(urlEqualTo("/price"))
                .willReturn(aResponse().withStatus(200).withBody("{\"price\": 80}")));
        Thread.sleep(1500);
        assertThat(executionsOf(workflowId)).hasSize(1);

        // back above, then below again: second edge fires
        WIRE_MOCK.stubFor(get(urlEqualTo("/price"))
                .willReturn(aResponse().withStatus(200).withBody("{\"price\": 120}")));
        Thread.sleep(1200);
        WIRE_MOCK.stubFor(get(urlEqualTo("/price"))
                .willReturn(aResponse().withStatus(200).withBody("{\"price\": 70}")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(executionsOf(workflowId)).hasSize(2));
    }

    private static String rssFeed(String... items) {
        StringBuilder entries = new StringBuilder();
        for (String item : items) {
            entries.append("""
                    <item><title>%s</title><link>https://example.com/%s</link>
                    <guid>https://example.com/%s</guid><description>desc %s</description></item>
                    """.formatted(item, item, item, item));
        }
        return """
                <?xml version="1.0"?><rss version="2.0"><channel>
                <title>test feed</title><link>https://example.com</link><description>t</description>
                %s</channel></rss>""".formatted(entries);
    }

    @Test
    void rssFiresOncePerNewItem() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(get(urlEqualTo("/feed")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/rss+xml")
                .withBody(rssFeed("one", "two"))));

        var created = postYaml("/api/workflows", """
                name: rss-test
                trigger:
                  rss: { interval: 300ms, url: "%s/feed" }
                steps:
                  - { name: noop, action: http, with: { url: "%s/ok", fail_on_status: false } }
                """.formatted(WIRE_MOCK.baseUrl(), WIRE_MOCK.baseUrl()));
        String workflowId = (String) created.getBody().get("id");

        // first poll = baseline: existing items don't fire
        Thread.sleep(1500);
        assertThat(executionsOf(workflowId)).isEmpty();

        WIRE_MOCK.stubFor(get(urlEqualTo("/feed")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/rss+xml")
                .withBody(rssFeed("one", "two", "three"))));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(executionsOf(workflowId)).hasSize(1));

        Map<String, Object> execution = getExecution(
                (String) executionsOf(workflowId).get(0).get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>)
                ((Map<String, Object>) execution.get("triggerInfo")).get("payload");
        assertThat(payload.get("title")).isEqualTo("three");
        assertThat(payload.get("link")).isEqualTo("https://example.com/three");

        // same feed again: dedupe holds
        Thread.sleep(1200);
        assertThat(executionsOf(workflowId)).hasSize(1);
    }
}
