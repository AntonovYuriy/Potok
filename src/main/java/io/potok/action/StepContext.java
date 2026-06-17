package io.potok.action;

import java.util.Map;
import java.util.UUID;

/**
 * Everything a handler gets for one step attempt.
 *
 * @param workflowId the workflow whose definition produced this step — handlers
 *                   that route to per-workflow state (e.g. M7 subscriptions in
 *                   the telegram action) read this; most ignore it
 * @param with    step inputs with all templates already resolved
 * @param attempt 1-based attempt number
 */
public record StepContext(
        UUID workflowId,
        UUID executionId,
        String workflowName,
        String stepName,
        Map<String, Object> with,
        int attempt) {

    public String requireString(String key) {
        Object value = with == null ? null : with.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("'" + key + "' is required for this action");
        }
        return value.toString();
    }

    public String optionalString(String key, String defaultValue) {
        Object value = with == null ? null : with.get(key);
        return value == null ? defaultValue : value.toString();
    }
}
