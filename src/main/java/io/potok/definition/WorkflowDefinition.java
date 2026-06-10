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
    public record Trigger(String cron, Webhook webhook, Poll poll, Rss rss) {
    }

    /**
     * {@code hmacSecretEnv} names an ENVIRONMENT VARIABLE holding the shared
     * secret — the secret itself never touches YAML or the database. When set,
     * deliveries must carry a valid GitHub-style X-Hub-Signature-256 header.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Webhook(String path, @JsonProperty("hmac_secret_env") String hmacSecretEnv) {
    }

    /**
     * HTTP poller: {@code fire_when} is either the literal "changed" (fire when
     * the response body hash changes) or a condition over the response
     * ({@code {status, body, headers}}) that fires on false→true transitions.
     */
    public record Poll(
            Duration interval,
            Map<String, Object> http,
            @JsonProperty("fire_when") String fireWhen,
            Extract extract) {
    }

    /**
     * Optional value extraction before compare/evaluate: jsonpath for JSON
     * responses, css selector (first match, text) for HTML. changed-mode then
     * hashes only the extracted value; expressions see it as {@code poll.value}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Extract(String jsonpath, String css) {
    }

    /** RSS/Atom poller: one execution per new feed item, deduped by guid/link. */
    public record Rss(Duration interval, String url) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(
            String name,
            String action,
            @JsonProperty("if") String condition,
            Map<String, Object> with,
            @JsonProperty("max_attempts") Integer maxAttempts,
            Retry retry,
            List<String> needs) {

        /** Effective max attempts: retry block wins over the legacy top-level field. */
        public Integer effectiveMaxAttempts() {
            if (retry != null && retry.maxAttempts() != null) {
                return retry.maxAttempts();
            }
            return maxAttempts;
        }
    }

    /**
     * Dependencies of a step: explicit {@code needs}, or — backward compatible
     * with linear M1/M2 workflows — the previous step in the list (first step
     * has none).
     */
    public List<String> effectiveNeeds(String stepName) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).name().equals(stepName)) {
                Step step = steps.get(i);
                if (step.needs() != null) {
                    return step.needs();
                }
                return i == 0 ? List.of() : List.of(steps.get(i - 1).name());
            }
        }
        return List.of();
    }

    /** Steps with no dependencies — where an execution starts. */
    public List<Step> rootSteps() {
        return steps.stream().filter(s -> effectiveNeeds(s.name()).isEmpty()).toList();
    }

    /** All transitive dependencies of a step (the only outputs it may reference). */
    public java.util.Set<String> needsClosure(String stepName) {
        java.util.Set<String> closure = new java.util.LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(effectiveNeeds(stepName));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (closure.add(current)) {
                queue.addAll(effectiveNeeds(current));
            }
        }
        return closure;
    }

    /** Steps that list the given step among their effective needs. */
    public List<Step> dependents(String stepName) {
        return steps.stream()
                .filter(s -> effectiveNeeds(s.name()).contains(stepName))
                .toList();
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

    /** Transitive dependents of a step — what a DLQ requeue needs to un-skip. */
    public java.util.Set<String> downstreamClosure(String stepName) {
        java.util.Set<String> closure = new java.util.LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(List.of(stepName));
        while (!queue.isEmpty()) {
            for (Step dependent : dependents(queue.poll())) {
                if (closure.add(dependent.name())) {
                    queue.add(dependent.name());
                }
            }
        }
        return closure;
    }
}
