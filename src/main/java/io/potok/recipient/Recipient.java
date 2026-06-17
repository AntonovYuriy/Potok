package io.potok.recipient;

import java.time.Instant;
import java.util.UUID;

/**
 * One Telegram chat that has messaged the bot. Holds the message-routing
 * relationship only; never grants any access to the Potok control plane.
 */
public record Recipient(
        UUID id,
        String chatId,
        String displayName,
        Status status,
        String source,
        Instant createdAt,
        Instant approvedAt,
        Instant lastSeenAt) {

    public enum Status { PENDING, APPROVED, REVOKED }
}
