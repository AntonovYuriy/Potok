package io.potok.execution;

import java.util.UUID;

/** Published when a job lands in the dead letter queue. */
public record DeadLetteredEvent(UUID executionId, String workflowName, String stepName, String error) {
}
