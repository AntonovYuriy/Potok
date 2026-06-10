package io.potok.execution;

import io.potok.definition.WorkflowDefinition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Fixed-backoff retry: per-step max_attempts (default from config), constant delay between attempts. */
@Component
public class RetryPolicy {

    private final int defaultMaxAttempts;
    private final Duration backoff;

    @org.springframework.beans.factory.annotation.Autowired
    public RetryPolicy(QueueProperties properties) {
        this(properties.defaultMaxAttempts(), properties.retryBackoff());
    }

    public RetryPolicy(int defaultMaxAttempts, Duration backoff) {
        this.defaultMaxAttempts = defaultMaxAttempts;
        this.backoff = backoff;
    }

    public int maxAttempts(WorkflowDefinition.Step step) {
        return step.maxAttempts() != null ? step.maxAttempts() : defaultMaxAttempts;
    }

    /** @param finishedAttempts attempts already completed, including the one that just failed */
    public boolean shouldRetry(int finishedAttempts, WorkflowDefinition.Step step) {
        return finishedAttempts < maxAttempts(step);
    }

    public Instant nextRunAt(Instant now) {
        return now.plus(backoff);
    }
}
