package io.potok.action;

/**
 * SPI for workflow step actions. Implementations are discovered as Spring beans;
 * adding an action = adding one bean, no registration code.
 */
public interface ActionHandler {

    /** Action type referenced by the {@code action:} field in YAML (e.g. "http"). */
    String type();

    StepResult execute(StepContext ctx);
}
