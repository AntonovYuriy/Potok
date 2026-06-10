package io.potok.execution;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** {@code definition} snapshots the parsed workflow at start; running executions ignore later edits. */
public record WorkflowExecution(
        UUID id,
        UUID workflowId,
        ExecutionStatus status,
        Map<String, Object> triggerInfo,
        Integer versionNo,
        io.potok.definition.WorkflowDefinition definition,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt) {
}
