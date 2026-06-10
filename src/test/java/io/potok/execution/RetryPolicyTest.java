package io.potok.execution;

import io.potok.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(30));

    private static WorkflowDefinition.Step step(Integer maxAttempts) {
        return new WorkflowDefinition.Step("s", "http", null, null, maxAttempts);
    }

    @Test
    void defaultMaxAttemptsIsThree() {
        assertThat(policy.maxAttempts(step(null))).isEqualTo(3);
        assertThat(policy.shouldRetry(1, step(null))).isTrue();
        assertThat(policy.shouldRetry(2, step(null))).isTrue();
        assertThat(policy.shouldRetry(3, step(null))).isFalse();
    }

    @Test
    void perStepMaxAttemptsOverridesDefault() {
        assertThat(policy.maxAttempts(step(1))).isEqualTo(1);
        assertThat(policy.shouldRetry(1, step(1))).isFalse();
        assertThat(policy.shouldRetry(4, step(5))).isTrue();
    }

    @Test
    void backoffIsFixed() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(policy.nextRunAt(now)).isEqualTo(now.plusSeconds(30));
    }
}
