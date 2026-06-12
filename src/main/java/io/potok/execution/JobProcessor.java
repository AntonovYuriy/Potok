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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes one claimed job: runs the step's action and advances the execution
 * (the DAG bookkeeping itself lives in {@link ExecutionAdvancer} so external
 * resumes — approval clicks — share it).
 *
 * Deliberately NOT transactional around the action call — actions do external
 * I/O, so the only guard while they run is the job_queue lease (locked_until).
 * That yields at-least-once semantics; re-deliveries of already-SUCCEEDED
 * steps are skipped, which is the idempotency contract.
 *
 * Durable pauses: a 'wait' step and a waiting approval park their job with
 * run_at in the future — pure rows in Postgres, so they survive restarts by
 * construction. An approval's wake-up at expires_at resolves it as a
 * timed-out RESULT (never a failure, never the DLQ).
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
    private final ExecutionAdvancer advancer;
    private final ApprovalService approvalService;
    private final ApprovalRepository approvals;

    public JobProcessor(WorkflowRepository workflows,
                        ExecutionRepository executions,
                        StepExecutionRepository steps,
                        JobQueueRepository jobQueue,
                        DeadLetterRepository deadLetters,
                        ActionRegistry actions,
                        TemplateResolver templates,
                        RetryPolicy retryPolicy,
                        PotokMetrics metrics,
                        ApplicationEventPublisher events,
                        ExecutionAdvancer advancer,
                        ApprovalService approvalService,
                        ApprovalRepository approvals) {
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
        this.advancer = advancer;
        this.approvalService = approvalService;
        this.approvals = approvals;
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
        // executions run their snapshot; pre-M4 rows (no snapshot) fall back to the live definition
        WorkflowDefinition definition = execution.definition() != null
                ? execution.definition() : workflow.definition();
        WorkflowDefinition.Step step = definition.step(job.stepName());
        if (step == null) {
            failExecution(job, execution.id(), "step '" + job.stepName() + "' not found in definition");
            return;
        }

        executions.markRunning(execution.id());

        // Idempotency: a step that already SUCCEEDED for this execution is never re-run.
        Optional<StepExecution> existing = steps.find(execution.id(), step.name());
        if (existing.isPresent() && existing.get().status() == StepStatus.SUCCEEDED) {
            onStepFinished(job, definition, workflow.name(), execution.id());
            return;
        }

        Map<String, Object> context = buildContext(execution);

        if (step.condition() != null && !templates.evaluateCondition(step.condition(), context)) {
            // condition-skip: downstream needs treat this as satisfied
            steps.markSkipped(execution.id(), step.name(), null);
            log.info("step_skipped executionId={} step={} condition={}",
                    execution.id(), step.name(), step.condition());
            onStepFinished(job, definition, workflow.name(), execution.id());
            return;
        }

        if (step.waitFor() != null) {
            handleWait(job, definition, workflow.name(), execution, step,
                    existing.map(StepExecution::status).orElse(null));
            return;
        }
        if ("approval".equals(step.action())) {
            handleApproval(job, definition, workflow, execution, step, context,
                    existing.map(StepExecution::status).orElse(null));
            return;
        }

        ActionHandler handler = actions.find(step.action());
        if (handler == null) {
            steps.markFailed(execution.id(), step.name(),
                    "unknown action '" + step.action() + "'; available: " + actions.types(), true);
            onStepFailedFinally(job, definition, workflow.name(), execution.id(), step);
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
            onStepFinished(job, definition, workflow.name(), execution.id());
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
            onStepFailedFinally(job, definition, workflow.name(), execution.id(), step);
            log.warn("step_failed executionId={} step={} attempts={} error={}",
                    execution.id(), step.name(), attempt, result.error());
        }
    }

    /**
     * Durable sleep: first delivery parks the job at now+wait; the wake-up
     * delivery (row already WAITING) completes the step. No retries — there
     * is nothing to fail.
     */
    private void handleWait(QueuedJob job, WorkflowDefinition definition, String workflowName,
                            WorkflowExecution execution, WorkflowDefinition.Step step,
                            StepStatus currentStatus) {
        if (currentStatus == StepStatus.WAITING) {
            steps.markSucceeded(execution.id(), step.name(), Map.of(
                    "slept", step.waitFor().toString(),
                    "woke_at", Instant.now().toString()));
            log.info("step_woke executionId={} step={}", execution.id(), step.name());
            onStepFinished(job, definition, workflowName, execution.id());
            return;
        }
        Instant wakeAt = Instant.now().plus(step.waitFor());
        steps.markWaiting(execution.id(), step.name(), Map.of("sleeping_until", wakeAt.toString()));
        executions.markWaiting(execution.id());
        jobQueue.rescheduleTo(job.id(), wakeAt);
        log.info("step_sleeping executionId={} step={} until={}", execution.id(), step.name(), wakeAt);
    }

    /**
     * Approval: first delivery asks (telegram failure follows normal retry
     * semantics) and parks the job at expires_at; the wake-up delivery — if
     * still undecided — resolves it as a timed-out RESULT. Link clicks finish
     * the step out-of-band via ApprovalService.
     */
    private void handleApproval(QueuedJob job, WorkflowDefinition definition, Workflow workflow,
                                WorkflowExecution execution, WorkflowDefinition.Step step,
                                Map<String, Object> context, StepStatus currentStatus) {
        if (currentStatus == StepStatus.WAITING) {
            Optional<Approval> approval = approvals.find(execution.id(), step.name());
            if (approval.isPresent() && approvals.decide(approval.get().id(), "timed_out")) {
                steps.markSucceeded(execution.id(), step.name(), Map.of(
                        "approved", false,
                        "timed_out", true,
                        "decided_at", Instant.now().toString()));
                log.info("approval_timed_out executionId={} step={}", execution.id(), step.name());
                onStepFinished(job, definition, workflow.name(), execution.id());
            } else {
                jobQueue.delete(job.id()); // decided in a race — the click path already advanced
            }
            return;
        }

        int attempt = job.attempts() + 1;
        @SuppressWarnings("unchecked")
        Map<String, Object> input = step.with() == null
                ? Map.of()
                : (Map<String, Object>) templates.resolve(step.with(), context);
        steps.markRunning(execution.id(), step.name(), attempt, input);
        try {
            Instant expiresAt = approvalService.ask(execution.id(), workflow.name(), step.name(), input);
            steps.markWaiting(execution.id(), step.name(), Map.of(
                    "waiting_for", "approval",
                    "expires_at", expiresAt.toString()));
            executions.markWaiting(execution.id());
            jobQueue.rescheduleTo(job.id(), expiresAt);
        } catch (Exception e) {
            String error = io.potok.common.Errors.describe(e);
            if (retryPolicy.shouldRetry(attempt, step)) {
                Instant nextRun = retryPolicy.nextRunAt(Instant.now(), attempt, step);
                steps.markFailed(execution.id(), step.name(), error, false);
                jobQueue.scheduleRetry(job.id(), nextRun);
                metrics.stepRetried();
                log.warn("approval_ask_retry executionId={} step={} attempt={} error={}",
                        execution.id(), step.name(), attempt, error);
            } else {
                steps.markFailed(execution.id(), step.name(), error, true);
                deadLetter(job, workflow, execution, step, attempt, error, input);
                onStepFailedFinally(job, definition, workflow.name(), execution.id(), step);
                log.warn("approval_ask_failed executionId={} step={} attempts={} error={}",
                        execution.id(), step.name(), attempt, error);
            }
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

    private void onStepFinished(QueuedJob job, WorkflowDefinition definition, String workflowName, UUID executionId) {
        advancer.onStepSatisfied(definition, workflowName, executionId);
        jobQueue.delete(job.id());
    }

    private void onStepFailedFinally(QueuedJob job, WorkflowDefinition definition, String workflowName,
                                     UUID executionId, WorkflowDefinition.Step failedStep) {
        advancer.onStepFailedFinally(definition, workflowName, executionId, failedStep.name());
        jobQueue.delete(job.id());
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
            return StepResult.fail(io.potok.common.Errors.describe(e));
        }
    }
}
