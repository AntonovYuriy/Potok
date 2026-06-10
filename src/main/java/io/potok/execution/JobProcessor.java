package io.potok.execution;

import io.potok.action.ActionHandler;
import io.potok.action.ActionRegistry;
import io.potok.action.StepContext;
import io.potok.action.StepResult;
import io.potok.definition.TemplateResolver;
import io.potok.definition.Workflow;
import io.potok.definition.WorkflowDefinition;
import io.potok.definition.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes one claimed job: runs the step's action and advances the execution.
 *
 * Deliberately NOT transactional around the action call — actions do external
 * I/O, so the only guard while they run is the job_queue lease (locked_until).
 * That yields at-least-once semantics; re-deliveries of already-SUCCEEDED
 * steps are skipped, which is the idempotency contract.
 *
 * Retry exhaustion moves the job to the dead_letter table (full context
 * snapshot) before failing the execution; /api/dlq can requeue it later.
 */
@Component
public class JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final WorkflowRepository workflows;
    private final ExecutionRepository executions;
    private final StepExecutionRepository steps;
    private final JobQueueRepository jobQueue;
    private final DeadLetterRepository deadLetters;
    private final ActionRegistry actions;
    private final TemplateResolver templates;
    private final RetryPolicy retryPolicy;
    private final PotokMetrics metrics;
    private final ApplicationEventPublisher events;

    public JobProcessor(WorkflowRepository workflows,
                        ExecutionRepository executions,
                        StepExecutionRepository steps,
                        JobQueueRepository jobQueue,
                        DeadLetterRepository deadLetters,
                        ActionRegistry actions,
                        TemplateResolver templates,
                        RetryPolicy retryPolicy,
                        PotokMetrics metrics,
                        ApplicationEventPublisher events) {
        this.workflows = workflows;
        this.executions = executions;
        this.steps = steps;
        this.jobQueue = jobQueue;
        this.deadLetters = deadLetters;
        this.actions = actions;
        this.templates = templates;
        this.retryPolicy = retryPolicy;
        this.metrics = metrics;
        this.events = events;
    }

    public void process(QueuedJob job) {
        MDC.put("execution_id", job.executionId().toString());
        try {
            doProcess(job);
        } finally {
            MDC.remove("execution_id");
            MDC.remove("workflow_name");
        }
    }

    private void doProcess(QueuedJob job) {
        Optional<WorkflowExecution> executionFound = executions.findById(job.executionId());
        if (executionFound.isEmpty()) {
            log.warn("job_dropped jobId={} reason=execution_missing", job.id());
            jobQueue.delete(job.id());
            return;
        }
        WorkflowExecution execution = executionFound.get();
        if (execution.status() == ExecutionStatus.SUCCEEDED || execution.status() == ExecutionStatus.FAILED) {
            jobQueue.delete(job.id());
            return;
        }

        Optional<Workflow> workflowFound = workflows.findById(execution.workflowId());
        if (workflowFound.isEmpty()) {
            failExecution(job, execution.id(), "workflow no longer exists");
            return;
        }
        Workflow workflow = workflowFound.get();
        MDC.put("workflow_name", workflow.name());
        WorkflowDefinition.Step step = workflow.definition().step(job.stepName());
        if (step == null) {
            failExecution(job, execution.id(), "step '" + job.stepName() + "' not found in definition");
            return;
        }

        executions.markRunning(execution.id());

        // Idempotency: a step that already SUCCEEDED for this execution is never re-run.
        Optional<StepExecution> existing = steps.find(execution.id(), step.name());
        if (existing.isPresent() && existing.get().status() == StepStatus.SUCCEEDED) {
            onStepFinished(job, workflow, execution.id());
            return;
        }

        Map<String, Object> context = buildContext(execution);

        if (step.condition() != null && !templates.evaluateCondition(step.condition(), context)) {
            // condition-skip: downstream needs treat this as satisfied
            steps.markSkipped(execution.id(), step.name(), null);
            log.info("step_skipped executionId={} step={} condition={}",
                    execution.id(), step.name(), step.condition());
            onStepFinished(job, workflow, execution.id());
            return;
        }

        ActionHandler handler = actions.find(step.action());
        if (handler == null) {
            steps.markFailed(execution.id(), step.name(),
                    "unknown action '" + step.action() + "'; available: " + actions.types(), true);
            onStepFailedFinally(job, workflow, execution.id(), step);
            return;
        }

        int attempt = job.attempts() + 1;
        @SuppressWarnings("unchecked")
        Map<String, Object> input = step.with() == null
                ? Map.of()
                : (Map<String, Object>) templates.resolve(step.with(), context);
        steps.markRunning(execution.id(), step.name(), attempt, input);

        Instant startedAt = Instant.now();
        StepResult result = executeSafely(handler,
                new StepContext(execution.id(), workflow.name(), step.name(), input, attempt));
        metrics.stepExecuted(step.action(), Duration.between(startedAt, Instant.now()), result.success());

        if (result.success()) {
            steps.markSucceeded(execution.id(), step.name(),
                    result.output() == null ? Map.of() : result.output());
            log.info("step_succeeded executionId={} step={} attempt={}", execution.id(), step.name(), attempt);
            onStepFinished(job, workflow, execution.id());
            return;
        }

        if (retryPolicy.shouldRetry(attempt, step)) {
            Instant nextRun = retryPolicy.nextRunAt(Instant.now(), attempt, step);
            steps.markFailed(execution.id(), step.name(), result.error(), false);
            jobQueue.scheduleRetry(job.id(), nextRun);
            metrics.stepRetried();
            log.warn("step_retry executionId={} step={} attempt={}/{} nextRunAt={} error={}",
                    execution.id(), step.name(), attempt, retryPolicy.maxAttempts(step), nextRun, result.error());
        } else {
            steps.markFailed(execution.id(), step.name(), result.error(), true);
            deadLetter(job, workflow, execution, step, attempt, result.error(), input);
            onStepFailedFinally(job, workflow, execution.id(), step);
            log.warn("step_failed executionId={} step={} attempts={} error={}",
                    execution.id(), step.name(), attempt, result.error());
        }
    }

    private void deadLetter(QueuedJob job, Workflow workflow, WorkflowExecution execution,
                            WorkflowDefinition.Step step, int attempts, String error,
                            Map<String, Object> input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("input", input);
        payload.put("trigger_info", execution.triggerInfo());
        deadLetters.insert(execution.id(), step.name(), attempts, error, payload);
        metrics.deadLettered();
        events.publishEvent(new DeadLetteredEvent(execution.id(), workflow.name(), step.name(), error));
        log.warn("job_dead_lettered executionId={} step={} attempts={}", execution.id(), step.name(), attempts);
    }

    /**
     * Step reached a satisfied terminal state (SUCCEEDED or condition-SKIPPED):
     * enqueue every step whose needs are now all satisfied, then finish the
     * execution if the whole graph is terminal.
     */
    private void onStepFinished(QueuedJob job, Workflow workflow, UUID executionId) {
        enqueueReadySteps(workflow, executionId);
        finishIfComplete(workflow, executionId);
        jobQueue.delete(job.id());
    }

    /**
     * Step failed for good: poison everything downstream (those steps are
     * SKIPPED, not FAILED), let independent branches keep running, finish when
     * the graph is terminal — final status will be FAILED.
     */
    private void onStepFailedFinally(QueuedJob job, Workflow workflow, UUID executionId,
                                     WorkflowDefinition.Step failedStep) {
        cascadeSkipDownstream(workflow.definition(), executionId, failedStep.name());
        finishIfComplete(workflow, executionId);
        jobQueue.delete(job.id());
    }

    static final String DEPENDENCY_FAILED_PREFIX = "dependency failed: ";

    private void cascadeSkipDownstream(WorkflowDefinition definition, UUID executionId, String failedName) {
        Map<String, StepExecution> rows = stepRowsByName(executionId);
        java.util.Set<String> poisoned = new java.util.HashSet<>();
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

    private void enqueueReadySteps(Workflow workflow, UUID executionId) {
        Map<String, StepExecution> rows = stepRowsByName(executionId);
        for (WorkflowDefinition.Step step : workflow.definition().steps()) {
            StepExecution row = rows.get(step.name());
            if (row != null) {
                continue; // terminal, running, or awaiting retry — all have a row
            }
            boolean ready = workflow.definition().effectiveNeeds(step.name()).stream()
                    .allMatch(need -> isSatisfied(rows.get(need)));
            if (ready) {
                // ON CONFLICT dedupes the join step when two branches finish at once
                jobQueue.enqueue(executionId, step.name(), Instant.now());
            }
        }
    }

    private void finishIfComplete(Workflow workflow, UUID executionId) {
        Map<String, StepExecution> rows = stepRowsByName(executionId);
        boolean allTerminal = workflow.definition().steps().stream()
                .allMatch(step -> isTerminal(rows.get(step.name())));
        if (!allTerminal) {
            return;
        }
        boolean anyFailed = rows.values().stream().anyMatch(r -> r.status() == StepStatus.FAILED);
        ExecutionStatus finalStatus = anyFailed ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED;
        if (executions.markFinished(executionId, finalStatus)) {
            if (anyFailed) {
                metrics.executionFailed();
                log.warn("execution_failed executionId={} workflow={}", executionId, workflow.name());
            } else {
                metrics.executionSucceeded();
                log.info("execution_succeeded executionId={} workflow={}", executionId, workflow.name());
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

    private void failExecution(QueuedJob job, UUID executionId, String reason) {
        executions.markFinished(executionId, ExecutionStatus.FAILED);
        metrics.executionFailed();
        jobQueue.delete(job.id());
        log.warn("execution_failed executionId={} reason={}", executionId, reason);
    }

    private Map<String, Object> buildContext(WorkflowExecution execution) {
        Map<String, Object> context = new LinkedHashMap<>();
        Object payload = execution.triggerInfo() == null ? null : execution.triggerInfo().get("payload");
        context.put("trigger", payload == null ? Map.of() : payload);
        context.put("steps", steps.succeededOutputs(execution.id()));
        return context;
    }

    private StepResult executeSafely(ActionHandler handler, StepContext ctx) {
        try {
            StepResult result = handler.execute(ctx);
            return result != null ? result : StepResult.fail("action returned no result");
        } catch (Exception e) {
            return StepResult.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
