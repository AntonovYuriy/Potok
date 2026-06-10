package io.potok.execution;

import io.potok.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private static final Duration BASE = Duration.ofSeconds(10);
    private static final Duration MAX = Duration.ofMinutes(10);

    private final RetryPolicy policy = new RetryPolicy(3, BASE, MAX, new Random(42));

    private static WorkflowDefinition.Step step(Integer maxAttempts) {
        return new WorkflowDefinition.Step("s", "http", null, null, maxAttempts, null, null);
    }

    private static WorkflowDefinition.Step stepWithRetry(Integer maxAttempts, Duration base, Duration max) {
        return new WorkflowDefinition.Step("s", "http", null, null, null,
                new WorkflowDefinition.Retry(maxAttempts, base, max), null);
    }

    @Test
    void defaultMaxAttemptsIsThree() {
        assertThat(policy.maxAttempts(step(null))).isEqualTo(3);
        assertThat(policy.shouldRetry(2, step(null))).isTrue();
        assertThat(policy.shouldRetry(3, step(null))).isFalse();
    }

    @Test
    void legacyMaxAttemptsStillWorks() {
        assertThat(policy.maxAttempts(step(1))).isEqualTo(1);
        assertThat(policy.shouldRetry(1, step(1))).isFalse();
    }

    @Test
    void retryBlockOverridesLegacyField() {
        WorkflowDefinition.Step step = new WorkflowDefinition.Step("s", "http", null, null, 2,
                new WorkflowDefinition.Retry(5, null, null), null);
        assertThat(policy.maxAttempts(step)).isEqualTo(5);
    }

    @Test
    void capDoublesPerAttempt() {
        assertThat(policy.delayCapMillis(1, step(null))).isEqualTo(BASE.toMillis());
        assertThat(policy.delayCapMillis(2, step(null))).isEqualTo(BASE.toMillis() * 2);
        assertThat(policy.delayCapMillis(3, step(null))).isEqualTo(BASE.toMillis() * 4);
    }

    @Test
    void capNeverExceedsMaxDelay() {
        // 10s * 2^9 = 5120s > 600s max
        assertThat(policy.delayCapMillis(10, step(null))).isEqualTo(MAX.toMillis());
        // absurd attempt counts must not overflow
        assertThat(policy.delayCapMillis(1000, step(null))).isEqualTo(MAX.toMillis());
    }

    @Test
    void perStepDelaysOverrideDefaults() {
        WorkflowDefinition.Step step = stepWithRetry(null, Duration.ofSeconds(1), Duration.ofSeconds(3));
        assertThat(policy.delayCapMillis(1, step)).isEqualTo(1000);
        assertThat(policy.delayCapMillis(2, step)).isEqualTo(2000);
        assertThat(policy.delayCapMillis(3, step)).isEqualTo(3000); // capped by max_delay
    }

    @Test
    void fullJitterStaysWithinBounds() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        for (int attempt = 1; attempt <= 8; attempt++) {
            long cap = policy.delayCapMillis(attempt, step(null));
            for (int i = 0; i < 200; i++) {
                Instant next = policy.nextRunAt(now, attempt, step(null));
                long delay = Duration.between(now, next).toMillis();
                assertThat(delay).isBetween(0L, cap);
            }
        }
    }

    @Test
    void jitterActuallyVaries() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        long distinct = java.util.stream.IntStream.range(0, 50)
                .mapToLong(i -> Duration.between(now, policy.nextRunAt(now, 3, step(null))).toMillis())
                .distinct()
                .count();
        assertThat(distinct).isGreaterThan(10);
    }
}
