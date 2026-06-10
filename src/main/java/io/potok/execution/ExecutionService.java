package io.potok.execution;

import io.potok.definition.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final ExecutionRepository executions;
    private final JobQueueRepository jobQueue;
    private final PotokMetrics metrics;

    public ExecutionService(ExecutionRepository executions, JobQueueRepository jobQueue, PotokMetrics metrics) {
        this.executions = executions;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
    }

    /**
     * Creates a PENDING execution and enqueues its first step. Atomic: either the
     * execution exists with a queued job, or nothing happened.
     */
    @Transactional
    public WorkflowExecution start(Workflow workflow, Map<String, Object> triggerInfo) {
        WorkflowExecution execution = executions.insert(workflow.id(), triggerInfo);
        // DAG: every dependency-free step starts immediately (linear flows have one root)
        for (var root : workflow.definition().rootSteps()) {
            jobQueue.enqueue(execution.id(), root.name(), Instant.now());
        }
        metrics.executionStarted();
        log.info("execution_started executionId={} workflow={} trigger={}",
                execution.id(), workflow.name(), triggerInfo.get("type"));
        return execution;
    }
}
