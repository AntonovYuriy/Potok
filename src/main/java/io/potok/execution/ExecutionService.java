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

    public ExecutionService(ExecutionRepository executions, JobQueueRepository jobQueue) {
        this.executions = executions;
        this.jobQueue = jobQueue;
    }

    /**
     * Creates a PENDING execution and enqueues its first step. Atomic: either the
     * execution exists with a queued job, or nothing happened.
     */
    @Transactional
    public WorkflowExecution start(Workflow workflow, Map<String, Object> triggerInfo) {
        WorkflowExecution execution = executions.insert(workflow.id(), triggerInfo);
        String firstStep = workflow.definition().steps().get(0).name();
        jobQueue.enqueue(execution.id(), firstStep, Instant.now());
        log.info("execution_started executionId={} workflow={} trigger={}",
                execution.id(), workflow.name(), triggerInfo.get("type"));
        return execution;
    }
}
