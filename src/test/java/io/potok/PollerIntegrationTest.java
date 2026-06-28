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

    private String createPollWithExtract(String name, String path, String jsonpath, String fireWhen) {
        var created = postYaml("/api/workflows", """
                name: %s
                trigger:
                  poll:
                    interval: 300ms
                    http: { method: GET, url: "%s%s" }
                    extract: { jsonpath: "%s" }
                    fire_when: "%s"
                steps:
                  - { name: noop, action: http, with: { url: "%s/ok", fail_on_status: false } }
                """.formatted(name, WIRE_MOCK.baseUrl(), path, jsonpath, fireWhen, WIRE_MOCK.baseUrl()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    @Test
    void nullAndErrorTicksNeverFireThenRecovers() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        // rate-limited (418): body has no price → must NOT be read as a value
        WIRE_MOCK.stubFor(get(urlEqualTo("/binance"))
                .willReturn(aResponse().withStatus(418).withBody("{\"code\": -1003}")));

        String workflowId = createPollWithExtract(
                "poll-null", "/binance", "$.price", "{{ poll.value < 59111 }}");

        Thread.sleep(1500);
        assertThat(executionsOf(workflowId)).as("418 must not fire").isEmpty();

        // 200 but the path is absent → extracted null → skip, no fire
        WIRE_MOCK.stubFor(get(urlEqualTo("/binance"))
                .willReturn(aResponse().withStatus(200).withBody("{\"other\": 1}")));
        Thread.sleep(1200);
        assertThat(executionsOf(workflowId)).as("null extract must not fire").isEmpty();

        // 200 with a real value ABOVE the threshold → condition false, no fire
        WIRE_MOCK.stubFor(get(urlEqualTo("/binance"))
                .willReturn(aResponse().withStatus(200).withBody("{\"price\": \"60000\"}")));
        Thread.sleep(1200);
        assertThat(executionsOf(workflowId)).as("above threshold must not fire").isEmpty();

        // 200 with a value BELOW the threshold → fires exactly once (false→true edge)
        WIRE_MOCK.stubFor(get(urlEqualTo("/binance"))
                .willReturn(aResponse().withStatus(200).withBody("{\"price\": \"58000\"}")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(executionsOf(workflowId)).hasSize(1));

        // stays below: edge already fired, no refire
        Thread.sleep(1200);
        assertThat(executionsOf(workflowId)).hasSize(1);
    }

    @Test
    void changedModeIgnoresNullAndErrorTicks() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(get(urlEqualTo("/feed"))
                .willReturn(aResponse().withStatus(200).withBody("{\"price\": \"100\"}")));

        String workflowId = createPollWithExtract("poll-changed-null", "/feed", "$.price", "changed");

        Thread.sleep(1200); // baseline value 100
        assertThat(executionsOf(workflowId)).isEmpty();

        // an outage (500) then a 200 missing the path must NOT count as "changed"
        WIRE_MOCK.stubFor(get(urlEqualTo("/feed"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        Thread.sleep(900);
        WIRE_MOCK.stubFor(get(urlEqualTo("/feed"))
                .willReturn(aResponse().withStatus(200).withBody("{\"nope\": 1}")));
        Thread.sleep(900);
        assertThat(executionsOf(workflowId)).as("error/null ticks are not a change").isEmpty();

        // back to the SAME value: still no change
        WIRE_MOCK.stubFor(get(urlEqualTo("/feed"))
                .willReturn(aResponse().withStatus(200).withBody("{\"price\": \"100\"}")));
        Thread.sleep(900);
        assertThat(executionsOf(workflowId)).isEmpty();

        // a real new value → fires once
        WIRE_MOCK.stubFor(get(urlEqualTo("/feed"))
                .willReturn(aResponse().withStatus(200).withBody("{\"price\": \"200\"}")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(executionsOf(workflowId)).hasSize(1));
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

    @Test
    void firstPollRunsImmediatelyAfterCreate() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        WIRE_MOCK.stubFor(get(urlEqualTo("/immediate"))
                .willReturn(aResponse().withStatus(200).withBody("{\"v\": 1}")));

        // 1h interval: only an immediate first tick can establish the baseline fast
        var created = postYaml("/api/workflows", """
                name: poll-immediate
                trigger:
                  poll:
                    interval: 1h
                    http: { method: GET, url: "%s/immediate" }
                    fire_when: "changed"
                steps:
                  - { name: noop, action: http, with: { url: "%s/ok", fail_on_status: false } }
                """.formatted(WIRE_MOCK.baseUrl(), WIRE_MOCK.baseUrl()));
        String workflowId = (String) created.getBody().get("id");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(pollStateExists(workflowId)).isTrue());
        // baseline never fires
        assertThat(executionsOf(workflowId)).isEmpty();
    }

    @org.springframework.beans.factory.annotation.Autowired
    io.potok.trigger.PollStateRepository pollStateRepository;

    private boolean pollStateExists(String workflowId) {
        return pollStateRepository.find(java.util.UUID.fromString(workflowId)).isPresent();
    }

    @Test
    void extractIgnoresNoisyBody() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(aResponse().withStatus(200)));
        // noisy timestamp changes every call; price is stable
        WIRE_MOCK.stubFor(get(urlEqualTo("/shop"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"ts\": \"%s\", \"product\": {\"price\": 149}}".formatted(System.nanoTime()))));

        var created = postYaml("/api/workflows", """
                name: poll-extract
                trigger:
                  poll:
                    interval: 300ms
                    http: { method: GET, url: "%s/shop" }
                    extract: { jsonpath: "$.product.price" }
                    fire_when: "changed"
                steps:
                  - { name: noop, action: http, with: { url: "%s/ok", fail_on_status: false } }
                """.formatted(WIRE_MOCK.baseUrl(), WIRE_MOCK.baseUrl()));
        String workflowId = (String) created.getBody().get("id");

        // several polls with changing ts but same price: no fire
        Thread.sleep(1500);
        WIRE_MOCK.stubFor(get(urlEqualTo("/shop"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"ts\": \"%s\", \"product\": {\"price\": 149}}".formatted(System.nanoTime()))));
        Thread.sleep(1200);
        assertThat(executionsOf(workflowId)).isEmpty();

        // price actually changes -> exactly one fire, payload carries the extracted value
        WIRE_MOCK.stubFor(get(urlEqualTo("/shop"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"ts\": \"x\", \"product\": {\"price\": 99}}")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(executionsOf(workflowId)).hasSize(1));

        Map<String, Object> execution = getExecution(
                (String) executionsOf(workflowId).get(0).get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>)
                ((Map<String, Object>) execution.get("triggerInfo")).get("payload");
        assertThat(payload.get("value")).isEqualTo(99);
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
