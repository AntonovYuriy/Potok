package io.potok.execution;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A claimed row from the job_queue table; {@code attempts} counts previously finished attempts. */
public record QueuedJob(
        long id,
        UUID executionId,
        String stepName,
        OffsetDateTime runAt,
        int attempts) {
}
