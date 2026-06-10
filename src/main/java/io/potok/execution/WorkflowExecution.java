package io.potok.execution;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record WorkflowExecution(
        UUID id,
        UUID workflowId,
        ExecutionStatus status,
        Map<String, Object> triggerInfo,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt) {
}
