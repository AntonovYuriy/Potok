package io.potok.definition;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One entry of the append-only version history (kept forever — it's tiny text). */
public record WorkflowVersion(
        UUID workflowId,
        int versionNo,
        String yamlSource,
        WorkflowDefinition definition,
        String comment,
        OffsetDateTime createdAt) {
}
