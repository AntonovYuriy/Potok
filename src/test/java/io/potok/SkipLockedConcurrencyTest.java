package io.potok;

import io.potok.action.ActionHandler;
import io.potok.action.StepContext;
import io.potok.action.StepResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SKIP LOCKED contract: with 2 workers polling concurrently, every step of
 * every execution runs exactly once — no double claims, nothing lost.
 */
@Import(SkipLockedConcurrencyTest.CountingActionConfig.class)
@TestPropertySource(properties = "potok.queue.workers=2")
class SkipLockedConcurrencyTest extends IntegrationTestBase {

    static final Map<String, AtomicInteger> INVOCATIONS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class CountingActionConfig {

        @Bean
        ActionHandler countingAction() {
            return new ActionHandler() {
                @Override
                public String type() {
                    return "counting";
                }

                @Override
                public StepResult execute(StepContext ctx) {
                    INVOCATIONS.computeIfAbsent(ctx.executionId() + "/" + ctx.stepName(),
                            k -> new AtomicInteger()).incrementAndGet();
                    try {
                        // widen the race window so a double claim would actually show up
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return StepResult.ok(Map.of("done", true));
                }
            };
        }
    }

    @Test
    void twoWorkersNeverExecuteTheSameStepTwice() {
        INVOCATIONS.clear();

        var created = postYaml("/api/workflows", """
                name: concurrency
                trigger:
                  webhook: { path: "load" }
                steps:
                  - { name: one, action: counting }
                  - { name: two, action: counting }
                """);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        int executionCount = 20;
        List<String> executionIds = new ArrayList<>();
        for (int i = 0; i < executionCount; i++) {
            executionIds.add((String) postJson("/hooks/load", Map.of("i", i)).getBody().get("executionId"));
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(executionIds)
                        .allSatisfy(id -> assertThat(getExecution(id).get("status")).isEqualTo("SUCCEEDED")));

        // 20 executions x 2 steps = 40 step runs, each exactly once
        assertThat(INVOCATIONS).hasSize(executionCount * 2);
        assertThat(INVOCATIONS).allSatisfy((key, count) ->
                assertThat(count.get()).as("step %s executed once", key).isEqualTo(1));
    }
}
