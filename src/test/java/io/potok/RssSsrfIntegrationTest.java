package io.potok;

import io.potok.trigger.PollStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSRF guard now covers the live RSS poller (M12), not just preview/http. A feed
 * URL that resolves to a private/internal address is refused on every scheduled tick.
 * Guard forced ON (production default); the WireMock feed lives on loopback, so it is
 * reachable and serving 200 — the ONLY thing that can stop a fire is the guard itself.
 */
class RssSsrfIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void guardOn(DynamicPropertyRegistry registry) {
        // overrides the test-classpath default (allow=true for WireMock on localhost)
        registry.add("potok.allow-private-urls", () -> "false");
    }

    @Autowired
    PollStateRepository pollState;
    @Autowired
    JdbcClient jdbc;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> executionsOf(String workflowId) {
        return rest.getForObject("/api/executions?workflowId=" + workflowId, List.class);
    }

    private long rssSeenRows(String workflowId) {
        return jdbc.sql("select count(*) from rss_seen where workflow_id = :id::uuid")
                .param("id", workflowId).query(Long.class).single();
    }

    @Test
    void rssPollToPrivateHostIsBlockedEvenThoughReachable() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/feed")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/rss+xml")
                .withBody("""
                        <?xml version="1.0"?><rss version="2.0"><channel>
                        <title>t</title><link>https://example.com</link><description>d</description>
                        <item><title>a</title><link>https://example.com/a</link>
                        <guid>https://example.com/a</guid></item>
                        </channel></rss>""")));

        var created = postYaml("/api/workflows", """
                name: rss-ssrf
                trigger: { rss: { interval: 300ms, url: "%s/feed" } }
                steps:
                  - { name: noop, action: http, with: { url: "%s/feed", fail_on_status: false } }
                """.formatted(WIRE_MOCK.baseUrl(), WIRE_MOCK.baseUrl()));
        String workflowId = (String) created.getBody().get("id");

        Thread.sleep(1500); // several poll ticks — each must be refused before fetching

        // guard returns before the fetch: no dedupe rows, no poll_state, no executions
        assertThat(rssSeenRows(workflowId)).as("blocked fetch stores nothing").isZero();
        assertThat(pollState.find(UUID.fromString(workflowId))).isEmpty();
        assertThat(executionsOf(workflowId)).isEmpty();
    }
}
