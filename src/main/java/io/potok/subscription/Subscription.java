package io.potok.subscription;

import java.time.Instant;
import java.util.UUID;

/** One recipient ↔ workflow link. Existence == subscribed. */
public record Subscription(UUID id, UUID workflowId, UUID recipientId, Instant createdAt) {
}
