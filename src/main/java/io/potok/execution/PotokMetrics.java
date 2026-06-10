package io.potok.execution;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** All potok_* meters in one place. Depth gauges hit the DB once per scrape. */
@Component
public class PotokMetrics {

    private final MeterRegistry registry;
    private final Counter executionsStarted;
    private final Counter executionsSucceeded;
    private final Counter executionsFailed;
    private final Counter stepRetries;
    private final Counter deadLettered;

    public PotokMetrics(MeterRegistry registry, JdbcClient jdbc) {
        this.registry = registry;
        this.executionsStarted = Counter.builder("potok.executions.started")
                .description("Workflow executions started").register(registry);
        this.executionsSucceeded = Counter.builder("potok.executions.succeeded")
                .description("Workflow executions finished successfully").register(registry);
        this.executionsFailed = Counter.builder("potok.executions.failed")
                .description("Workflow executions finished in FAILED").register(registry);
        this.stepRetries = Counter.builder("potok.step.retries")
                .description("Step attempts that were rescheduled for retry").register(registry);
        this.deadLettered = Counter.builder("potok.dlq.added")
                .description("Jobs moved to the dead letter queue").register(registry);
        Gauge.builder("potok.queue.depth", jdbc, PotokMetrics::queueDepth)
                .description("Rows currently in job_queue").register(registry);
        Gauge.builder("potok.dlq.size", jdbc, PotokMetrics::dlqSize)
                .description("Rows currently in dead_letter").register(registry);
    }

    public void executionStarted() {
        executionsStarted.increment();
    }

    public void executionSucceeded() {
        executionsSucceeded.increment();
    }

    public void executionFailed() {
        executionsFailed.increment();
    }

    public void stepRetried() {
        stepRetries.increment();
    }

    public void deadLettered() {
        deadLettered.increment();
    }

    public void stepExecuted(String action, Duration duration, boolean success) {
        Timer.builder("potok.step.duration")
                .description("Step action execution time")
                .tag("action", action)
                .tag("outcome", success ? "success" : "failure")
                .register(registry)
                .record(duration);
        if (!success) {
            Counter.builder("potok.action.failures")
                    .description("Failed action attempts")
                    .tag("action", action)
                    .register(registry)
                    .increment();
        }
    }

    private static double queueDepth(JdbcClient jdbc) {
        return count(jdbc, "select count(*) from job_queue");
    }

    private static double dlqSize(JdbcClient jdbc) {
        return count(jdbc, "select count(*) from dead_letter");
    }

    private static double count(JdbcClient jdbc, String sql) {
        try {
            return jdbc.sql(sql).query(Long.class).single();
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
