package io.potok;

import io.potok.action.ActionHandler;
import io.potok.action.StepContext;
import io.potok.action.StepResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** DAG semantics: diamond parallelism, branch failure -> SKIPPED downstream, sibling branch survives. */
@Import(DagIntegrationTest.TimedActionConfig.class)
@TestPropertySource(properties = "potok.queue.workers=4")
class DagIntegrationTest extends IntegrationTestBase {

    record Window(Instant start, Instant end) {
    }

    static final Map<String, Window> WINDOWS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class TimedActionConfig {

        /** Sleeps 400ms and records its execution window — enough to prove overlap. */
        @Bean
        ActionHandler timedAction() {
            return new ActionHandler() {
                @Override
                public String type() {
                    return "timed";
                }

                @Override
                public StepResult execute(StepContext ctx) {
                    Instant start = Instant.now();
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    WINDOWS.put(ctx.stepName(), new Window(start, Instant.now()));
                    return StepResult.ok(Map.of("step", ctx.stepName()));
                }
            };
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diamondRunsBranchesInParallel() {
        WINDOWS.clear();
        postYaml("/api/workflows", """
                name: diamond
                trigger:
                  webhook: { path: "diamond" }
                steps:
                  - { name: a, action: timed }
                  - { name: b, action: timed, needs: [a] }
                  - { name: c, action: timed, needs: [a] }
                  - { name: d, action: timed, needs: [b, c] }
                """);

        String executionId = (String) postJson("/hooks/diamond", Map.of()).getBody().get("executionId");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) getExecution(executionId).get("steps");
        assertThat(steps).extracting(s -> s.get("status")).containsOnly("SUCCEEDED");
        assertThat(steps).hasSize(4);

        // b and c actually overlapped in time
        Window b = WINDOWS.get("b");
        Window c = WINDOWS.get("c");
        assertThat(b.start()).isBefore(c.end());
        assertThat(c.start()).isBefore(b.end());
        // d ran only after both
        Window d = WINDOWS.get("d");
        assertThat(d.start()).isAfterOrEqualTo(b.end());
        assertThat(d.start()).isAfterOrEqualTo(c.end());
    }

    @Test
    @SuppressWarnings("unchecked")
    void branchFailureSkipsDownstreamSiblingCompletes() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/dag-broken"))
                .willReturn(aResponse().withStatus(500)));

        postYaml("/api/workflows", """
                name: dag-failure
                trigger:
                  webhook: { path: "dag-failure" }
                steps:
                  - { name: a, action: timed }
                  - name: broken
                    action: http
                    needs: [a]
                    retry: { max_attempts: 1 }
                    with: { method: GET, url: "%s/dag-broken" }
                  - { name: after-broken, action: timed, needs: [broken] }
                  - { name: healthy, action: timed, needs: [a] }
                  - { name: after-healthy, action: timed, needs: [healthy] }
                """.formatted(WIRE_MOCK.baseUrl()));

        String executionId = (String) postJson("/hooks/dag-failure", Map.of()).getBody().get("executionId");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("FAILED"));

        Map<String, String> statuses = new java.util.HashMap<>();
        Map<String, String> errors = new java.util.HashMap<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) getExecution(executionId).get("steps")) {
            statuses.put((String) s.get("name"), (String) s.get("status"));
            errors.put((String) s.get("name"), (String) s.get("error"));
        }
        assertThat(statuses).containsEntry("a", "SUCCEEDED");
        assertThat(statuses).containsEntry("broken", "FAILED");
        assertThat(statuses).containsEntry("after-broken", "SKIPPED");
        assertThat(errors.get("after-broken")).contains("dependency failed: broken");
        // independent branch ran to completion despite the failure
        assertThat(statuses).containsEntry("healthy", "SUCCEEDED");
        assertThat(statuses).containsEntry("after-healthy", "SUCCEEDED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void conditionSkipSatisfiesDownstreamNeeds() {
        postYaml("/api/workflows", """
                name: dag-cond-skip
                trigger:
                  webhook: { path: "dag-cond-skip" }
                steps:
                  - { name: a, action: timed }
                  - name: maybe
                    action: timed
                    needs: [a]
                    if: "{{ trigger.flag == 'yes' }}"
                  - { name: final, action: timed, needs: [maybe] }
                """);

        String executionId = (String) postJson("/hooks/dag-cond-skip", Map.of("flag", "no"))
                .getBody().get("executionId");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        Map<String, String> statuses = new java.util.HashMap<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) getExecution(executionId).get("steps")) {
            statuses.put((String) s.get("name"), (String) s.get("status"));
        }
        // condition-skip does NOT poison downstream — final still runs
        assertThat(statuses).containsEntry("maybe", "SKIPPED");
        assertThat(statuses).containsEntry("final", "SUCCEEDED");
    }
}
