package io.potok;

import io.potok.action.ActionHandler;
import io.potok.action.StepContext;
import io.potok.action.StepResult;
import io.potok.execution.JobProcessor;
import io.potok.execution.JobQueueRepository;
import io.potok.execution.QueueWorker;
import io.potok.execution.QueuedJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Graceful shutdown contract (the same QueueWorker.stop() path SIGTERM triggers
 * via Spring's shutdown hook): when an in-flight step cannot finish within the
 * grace period, its lease is released immediately — the job is claimable right
 * away, NOT after the (here: 10 minute) lock timeout expires.
 */
@Import(GracefulShutdownIntegrationTest.SlowActionConfig.class)
@TestPropertySource(properties = {
        "potok.queue.workers=1",
        "potok.queue.lock-timeout=PT10M",
        "potok.queue.shutdown-grace=PT0.3S",
})
class GracefulShutdownIntegrationTest extends IntegrationTestBase {

    static final CountDownLatch STARTED = new CountDownLatch(1);
    static final AtomicInteger CALLS = new AtomicInteger();

    @TestConfiguration
    static class SlowActionConfig {

        @Bean
        ActionHandler slowAction() {
            return new ActionHandler() {
                @Override
                public String type() {
                    return "slow";
                }

                @Override
                public StepResult execute(StepContext ctx) {
                    if (CALLS.incrementAndGet() == 1) {
                        STARTED.countDown();
                        try {
                            Thread.sleep(5_000); // far beyond the 0.3s grace
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return StepResult.ok(Map.of("done", true));
                }
            };
        }
    }

    @Autowired
    QueueWorker queueWorker;
    @Autowired
    JobQueueRepository jobQueue;
    @Autowired
    JobProcessor jobProcessor;

    @Test
    void shutdownReleasesInFlightLockForImmediatePickup() throws Exception {
        postYaml("/api/workflows", """
                name: shutdown-test
                trigger:
                  webhook: { path: "shutdown" }
                steps:
                  - { name: crawl, action: slow }
                """);

        String executionId = (String) postJson("/hooks/shutdown", Map.of()).getBody().get("executionId");

        assertThat(STARTED.await(10, TimeUnit.SECONDS))
                .as("step went in-flight")
                .isTrue();

        long stopStarted = System.nanoTime();
        queueWorker.stop();
        long stopMillis = Duration.ofNanos(System.nanoTime() - stopStarted).toMillis();
        assertThat(stopMillis)
                .as("stop() waits for grace, not for the step to finish")
                .isLessThan(4_000);

        // The lease was released: with a 10-minute lock timeout the job would otherwise
        // be invisible — another instance (here: this test) must be able to claim it NOW.
        Optional<QueuedJob> reclaimed = jobQueue.pollAndLock(Duration.ofMinutes(10));
        assertThat(reclaimed).as("job claimable immediately after shutdown").isPresent();
        assertThat(reclaimed.get().executionId().toString()).isEqualTo(executionId);

        // "Restarted instance" processes it to completion.
        jobProcessor.process(reclaimed.get());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        queueWorker.start(); // restore for any later tests sharing this context
    }
}
