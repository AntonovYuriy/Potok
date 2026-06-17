package io.potok.api;

import io.potok.definition.Workflow;
import io.potok.definition.WorkflowDefinition;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WorkflowResponse(
        UUID id,
        String name,
        boolean enabled,
        boolean subscribable,
        WorkflowDefinition definition,
        String yamlSource,
        int currentVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static WorkflowResponse from(Workflow workflow) {
        return new WorkflowResponse(
                workflow.id(),
                workflow.name(),
                workflow.enabled(),
                workflow.subscribable(),
                workflow.definition(),
                workflow.yamlSource(),
                workflow.currentVersion(),
                workflow.createdAt(),
                workflow.updatedAt());
    }
}
