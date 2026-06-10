package io.potok.action;

import java.util.Map;

/** Outcome of one step attempt; {@code output} becomes available to later steps as {@code steps.<name>.*}. */
public record StepResult(boolean success, Map<String, Object> output, String error) {

    public static StepResult ok(Map<String, Object> output) {
        return new StepResult(true, output, null);
    }

    public static StepResult fail(String error) {
        return new StepResult(false, null, error);
    }
}
