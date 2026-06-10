package io.potok.definition;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Stored workflow: YAML source of truth plus its parsed jsonb projection. */
public record Workflow(
        UUID id,
        String name,
        boolean enabled,
        String yamlSource,
        WorkflowDefinition definition,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
