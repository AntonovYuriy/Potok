package io.potok.execution;

import io.potok.definition.WorkflowDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter (AWS style):
 * delay = random(0, min(max_delay, base_delay * 2^(attempt-1))).
 * Per-step YAML overrides via retry: {max_attempts, base_delay, max_delay};
 * the legacy top-level max_attempts keeps working.
 */
@Component
public class RetryPolicy {

    private final int defaultMaxAttempts;
    private final Duration defaultBaseDelay;
    private final Duration defaultMaxDelay;
    private final RandomGenerator random;

    @Autowired
    public RetryPolicy(QueueProperties properties) {
        this(properties.defaultMaxAttempts(), properties.retryBaseDelay(), properties.retryMaxDelay(),
                RandomGenerator.getDefault());
    }

    public RetryPolicy(int defaultMaxAttempts, Duration baseDelay, Duration maxDelay, RandomGenerator random) {
        this.defaultMaxAttempts = defaultMaxAttempts;
        this.defaultBaseDelay = baseDelay;
        this.defaultMaxDelay = maxDelay;
        this.random = random;
    }

    public int maxAttempts(WorkflowDefinition.Step step) {
        Integer perStep = step.effectiveMaxAttempts();
        return perStep != null ? perStep : defaultMaxAttempts;
    }

    /** @param finishedAttempts attempts already completed, including the one that just failed */
    public boolean shouldRetry(int finishedAttempts, WorkflowDefinition.Step step) {
        return finishedAttempts < maxAttempts(step);
    }

    public Instant nextRunAt(Instant now, int finishedAttempts, WorkflowDefinition.Step step) {
        long capMillis = delayCapMillis(finishedAttempts, step);
        return now.plusMillis(random.nextLong(capMillis + 1));
    }

    /** Upper bound of the jittered delay: min(max_delay, base * 2^(attempt-1)), overflow-safe. */
    long delayCapMillis(int finishedAttempts, WorkflowDefinition.Step step) {
        WorkflowDefinition.Retry retry = step.retry();
        long baseMillis = (retry != null && retry.baseDelay() != null
                ? retry.baseDelay() : defaultBaseDelay).toMillis();
        long maxMillis = (retry != null && retry.maxDelay() != null
                ? retry.maxDelay() : defaultMaxDelay).toMillis();
        int exponent = Math.max(0, finishedAttempts - 1);
        // base * 2^exponent without overflow: bail to max once the shift can exceed it
        if (exponent >= 63 || baseMillis > (maxMillis >> Math.min(exponent, 62))) {
            return maxMillis;
        }
        return Math.min(maxMillis, baseMillis << exponent);
    }
}
