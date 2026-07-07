package io.potok.action;

import java.util.Map;

/**
 * Outcome of one step attempt; {@code output} becomes available to later steps
 * as {@code steps.<name>.*}. A {@code permanent} failure is one that retrying
 * cannot fix (missing/invalid step parameters, SSRF-blocked URL) — the engine
 * dead-letters it immediately instead of burning the retry budget.
 */
public record StepResult(boolean success, Map<String, Object> output, String error, boolean permanent) {

    public StepResult(boolean success, Map<String, Object> output, String error) {
        this(success, output, error, false);
    }

    public static StepResult ok(Map<String, Object> output) {
        return new StepResult(true, output, null, false);
    }

    public static StepResult fail(String error) {
        return new StepResult(false, null, error, false);
    }

    public static StepResult permanentFail(String error) {
        return new StepResult(false, null, error, true);
    }
}
