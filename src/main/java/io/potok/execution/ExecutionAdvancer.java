package io.potok.execution;

import io.potok.definition.WorkflowDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Moves an execution's DAG forward after a step reaches a terminal state.
 * Extracted from JobProcessor so out-of-band resumes (an approval click)
 * advance the graph through exactly the same code path as the workers.
 */
@Component
public class ExecutionAdvancer {

    static final String DEPENDENCY_FAILED_PREFIX = "dependency failed: ";

    private static final Logger log = LoggerFactory.getLogger(ExecutionAdvancer.class);

    private final StepExecutionRepository steps;
    private final ExecutionRepository executions;
    private final JobQueueRepository jobQueue;
    private final PotokMetrics metrics;

    public ExecutionAdvancer(StepExecutionRepository steps,
                             ExecutionRepository executions,
                             JobQueueRepository jobQueue,
                             PotokMetrics metrics) {
        this.steps = steps;
        this.executions = executions;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
    }

    /** Step satisfied (SUCCEEDED or condition-SKIPPED): enqueue ready steps, finish if terminal. */
    public void onStepSatisfied(WorkflowDefinition definition, String workflowName, UUID executionId) {
        enqueueReadySteps(definition, executionId);
        finishIfComplete(definition, workflowName, executionId);
    }

    /** Step failed for good: poison downstream, let independent branches run, finish if terminal. */
    public void onStepFailedFinally(WorkflowDefinition definition, String workflowName,
                                    UUID executionId, String failedStepName) {
        cascadeSkipDownstream(definition, executionId, failedStepName);
        finishIfComplete(definition, workflowName, executionId);
    }

    /** Resume after an external decision: loads the pinned definition and advances. */
    public void resume(WorkflowExecution execution, String workflowName) {
        WorkflowDefinition definition = execution.definition();
        if (definition == null) {
            log.warn("resume_without_definition executionId={}", execution.id());
            return;
        }
        executions.markRunning(execution.id());
        onStepSatisfied(definition, workflowName, execution.id());
    }

    private void cascadeSkipDownstream(WorkflowDefinition definition, UUID executionId, String failedName) {
        Map<String, StepExecution> rows = stepRowsByName(executionId);
        Set<String> poisoned = new HashSet<>();
        poisoned.add(failedName);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (WorkflowDefinition.Step step : definition.steps()) {
                if (poisoned.contains(step.name()) || isTerminal(rows.get(step.name()))) {
                    continue;
                }
                List<String> needs = definition.effectiveNeeds(step.name());
                Optional<String> badNeed = needs.stream().filter(poisoned::contains).findFirst();
                if (badNeed.isPresent()) {
                    steps.markSkipped(executionId, step.name(),
                            DEPENDENCY_FAILED_PREFIX + badNeed.get());
                    log.info("step_skipped_dependency executionId={} step={} failedDependency={}",
                            executionId, step.name(), badNeed.get());
                    poisoned.add(step.name());
                    changed = true;
                }
            }
        }
    }

    private void enqueueReadySteps(WorkflowDefinition definition, UUID executionId) {
        Map<String, StepExecution> rows = stepRowsByName(executionId);
        for (WorkflowDefinition.Step step : definition.steps()) {
            StepExecution row = rows.get(step.name());
            if (row != null) {
                continue; // terminal, running, waiting, or awaiting retry — all have a row
            }
            boolean ready = definition.effectiveNeeds(step.name()).stream()
                    .allMatch(need -> isSatisfied(rows.get(need)));
            if (ready) {
                // ON CONFLICT dedupes the join step when two branches finish at once
                jobQueue.enqueue(executionId, step.name(), Instant.now());
            }
        }
    }

    private void finishIfComplete(WorkflowDefinition definition, String workflowName, UUID executionId) {
        Map<String, StepExecution> rows = stepRowsByName(executionId);
        boolean allTerminal = definition.steps().stream()
                .allMatch(step -> isTerminal(rows.get(step.name())));
        if (!allTerminal) {
            return;
        }
        boolean anyFailed = rows.values().stream().anyMatch(r -> r.status() == StepStatus.FAILED);
        ExecutionStatus finalStatus = anyFailed ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED;
        if (executions.markFinished(executionId, finalStatus)) {
            if (anyFailed) {
                metrics.executionFailed();
                log.warn("execution_failed executionId={} workflow={}", executionId, workflowName);
            } else {
                metrics.executionSucceeded();
                log.info("execution_succeeded executionId={} workflow={}", executionId, workflowName);
            }
        }
    }

    private Map<String, StepExecution> stepRowsByName(UUID executionId) {
        Map<String, StepExecution> byName = new LinkedHashMap<>();
        for (StepExecution row : steps.findByExecution(executionId)) {
            byName.put(row.stepName(), row);
        }
        return byName;
    }

    private static boolean isTerminal(StepExecution row) {
        return row != null && (row.status() == StepStatus.SUCCEEDED
                || row.status() == StepStatus.FAILED
                || row.status() == StepStatus.SKIPPED);
    }

    /** SUCCEEDED satisfies a need; so does SKIPPED — dependency-skips cascade eagerly, so any SKIPPED seen here is a condition-skip. */
    private static boolean isSatisfied(StepExecution row) {
        return row != null && (row.status() == StepStatus.SUCCEEDED || row.status() == StepStatus.SKIPPED);
    }
}
