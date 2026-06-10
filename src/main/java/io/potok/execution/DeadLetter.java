package io.potok.execution;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** A job that exhausted its retries; payload snapshots the step input and trigger info. */
public record DeadLetter(
        long id,
        UUID executionId,
        String stepName,
        int attempts,
        String lastError,
        Map<String, Object> payload,
        OffsetDateTime createdAt) {
}
