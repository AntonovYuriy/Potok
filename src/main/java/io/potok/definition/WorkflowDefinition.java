package io.potok.definition;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Parsed workflow definition. JSON shape mirrors the YAML source so that
 * SQL queries over the jsonb column (e.g. webhook path lookup) stay obvious.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowDefinition(
        String name,
        Trigger trigger,
        List<Step> steps) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Trigger(String cron, Webhook webhook) {
    }

    public record Webhook(String path) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(
            String name,
            String action,
            @JsonProperty("if") String condition,
            Map<String, Object> with,
            @JsonProperty("max_attempts") Integer maxAttempts,
            Retry retry) {

        /** Effective max attempts: retry block wins over the legacy top-level field. */
        public Integer effectiveMaxAttempts() {
            if (retry != null && retry.maxAttempts() != null) {
                return retry.maxAttempts();
            }
            return maxAttempts;
        }
    }

    /** Per-step retry overrides; null fields fall back to engine defaults. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Retry(
            @JsonProperty("max_attempts") Integer maxAttempts,
            @JsonProperty("base_delay") Duration baseDelay,
            @JsonProperty("max_delay") Duration maxDelay) {
    }

    public Step step(String stepName) {
        return steps.stream()
                .filter(s -> s.name().equals(stepName))
                .findFirst()
                .orElse(null);
    }

    /** Returns the step after the given one, or null when it is the last. */
    public Step nextStep(String stepName) {
        for (int i = 0; i < steps.size() - 1; i++) {
            if (steps.get(i).name().equals(stepName)) {
                return steps.get(i + 1);
            }
        }
        return null;
    }
}
