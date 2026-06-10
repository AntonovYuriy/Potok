package io.potok.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.potok.execution.StepExecution;
import io.potok.execution.WorkflowExecution;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionResponse(
        UUID id,
        UUID workflowId,
        String status,
        Map<String, Object> triggerInfo,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt,
        List<StepResponse> steps) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepResponse(
            String name,
            String status,
            int attempt,
            Map<String, Object> input,
            Map<String, Object> output,
            String error,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {

        static StepResponse from(StepExecution step) {
            return new StepResponse(
                    step.stepName(),
                    step.status().name(),
                    step.attempt(),
                    step.input(),
                    step.output(),
                    step.error(),
                    step.startedAt(),
                    step.finishedAt());
        }
    }

    public static ExecutionResponse from(WorkflowExecution execution, List<StepExecution> steps) {
        return new ExecutionResponse(
                execution.id(),
                execution.workflowId(),
                execution.status().name(),
                execution.triggerInfo(),
                execution.startedAt(),
                execution.finishedAt(),
                execution.createdAt(),
                steps == null ? null : steps.stream().map(StepResponse::from).toList());
    }
}
