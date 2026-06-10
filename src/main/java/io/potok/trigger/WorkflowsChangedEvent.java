package io.potok.trigger;

/** Published by the API layer after any workflow create/update/delete so cron schedules refresh immediately. */
public record WorkflowsChangedEvent() {
}
