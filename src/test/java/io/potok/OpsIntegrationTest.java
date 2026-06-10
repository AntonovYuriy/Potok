package io.potok;

import io.potok.definition.Workflow;
import io.potok.definition.WorkflowRepository;
import io.potok.trigger.CronFireService;
import io.potok.trigger.PollerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Admin purge auth + multi-instance duplicate-fire protection. */
@TestPropertySource(properties = "potok.api-key=root-ops-key")
class OpsIntegrationTest extends IntegrationTestBase {

    @Autowired
    CronFireService cronFire;
    @Autowired
    PollerService pollerService;
    @Autowired
    WorkflowRepository workflows;
    @Autowired
    io.potok.trigger.PollStateRepository pollState;

    private HttpHeaders auth(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", key);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminPurgeRequiresRootKey() {
        // a regular token is NOT enough
        ResponseEntity<Map<String, Object>> tokenResp = rest.exchange("/api/tokens", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "t"), auth("root-ops-key")), MAP_TYPE);
        String token = (String) tokenResp.getBody().get("token");

        assertThat(rest.exchange("/api/admin/purge", HttpMethod.POST,
                new HttpEntity<>(Map.of(), auth(token)), MAP_TYPE).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map<String, Object>> root = rest.exchange("/api/admin/purge", HttpMethod.POST,
                new HttpEntity<>(Map.of(), auth("root-ops-key")), MAP_TYPE);
        assertThat(root.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(root.getBody()).containsKey("purgedExecutions");
    }

    private Workflow createWorkflow(String yaml) {
        HttpHeaders headers = auth("root-ops-key");
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<Map<String, Object>> created = rest.exchange("/api/workflows", HttpMethod.POST,
                new HttpEntity<>(yaml, headers), MAP_TYPE);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return workflows.findById(java.util.UUID.fromString((String) created.getBody().get("id"))).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> executionsOf(String workflowId) {
        return rest.exchange("/api/executions?workflowId=" + workflowId, HttpMethod.GET,
                new HttpEntity<>(auth("root-ops-key")), List.class).getBody();
    }

    @Test
    void cronClaimDedupesSameMinuteFires() {
        Workflow workflow = createWorkflow("""
                name: ops-cron
                trigger:
                  cron: "0 0 1 1 *"
                steps:
                  - { name: a, action: http, with: { url: "https://example.com", fail_on_status: false } }
                """);

        // simulate two replicas firing the same scheduled minute
        cronFire.fire(workflow);
        cronFire.fire(workflow);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(executionsOf(workflow.id().toString())).hasSize(1));
    }

    @Test
    void advisoryLockPreventsConcurrentPollDoubleFire() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/ops-poll"))
                .willReturn(aResponse().withStatus(200).withBody("{\"v\": 1}")));
        Workflow workflow = createWorkflow("""
                name: ops-poll
                trigger:
                  poll:
                    interval: 1h
                    http: { method: GET, url: "%s/ops-poll" }
                    fire_when: "changed"
                steps:
                  - { name: a, action: http, with: { url: "%s/ops-poll", fail_on_status: false } }
                """.formatted(WIRE_MOCK.baseUrl(), WIRE_MOCK.baseUrl()));

        // the scheduler's immediate first tick does the baseline (and briefly holds
        // the advisory lock) — wait for it before simulating replicas
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(pollState.find(workflow.id())).isPresent());
        WIRE_MOCK.stubFor(get(urlEqualTo("/ops-poll"))
                .willReturn(aResponse().withStatus(200).withBody("{\"v\": 2}")));

        // two "replicas" poll simultaneously; the lock + serialized state allow one fire
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable tick = () -> {
            try {
                start.await();
                pollerService.pollHttp(workflow);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var f1 = pool.submit(tick);
        var f2 = pool.submit(tick);
        start.countDown();
        f1.get();
        f2.get();
        pool.shutdown();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(executionsOf(workflow.id().toString())).hasSize(1));
    }
}
