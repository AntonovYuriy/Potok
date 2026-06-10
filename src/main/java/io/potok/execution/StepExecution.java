package io.potok.execution;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record StepExecution(
        UUID id,
        UUID executionId,
        String stepName,
        StepStatus status,
        int attempt,
        Map<String, Object> input,
        Map<String, Object> output,
        String error,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt) {
}
