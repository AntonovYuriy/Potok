package io.potok.execution;

import java.time.Instant;
import java.util.UUID;

/** One waiting (or decided) approval; token hashes only, never plaintext. */
public record Approval(
        UUID id,
        UUID executionId,
        String stepName,
        String workflowName,
        Instant expiresAt,
        Instant decidedAt,
        String decision,
        String chatId,
        Long messageId,
        String question) {

    public boolean isDecided() {
        return decidedAt != null;
    }
}
