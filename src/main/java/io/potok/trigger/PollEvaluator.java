package io.potok.trigger;

/**
 * Pure poll-firing decision. Two modes:
 * - "changed": fire when the body hash differs from the stored one; the very
 *   first poll only records the baseline and never fires.
 * - expression: fire on false→true transitions of the condition
 *   (edge-triggered — a condition that stays true fires once, not every poll).
 */
public final class PollEvaluator {

    public record Decision(boolean fire, String newHash, Boolean newCondition) {
    }

    private PollEvaluator() {
    }

    public static Decision changed(String previousHash, String newHash) {
        boolean fire = previousHash != null && !previousHash.equals(newHash);
        return new Decision(fire, newHash, null);
    }

    public static Decision expression(Boolean previousValue, boolean newValue, String newHash) {
        boolean fire = Boolean.FALSE.equals(previousValue) && newValue;
        return new Decision(fire, newHash, newValue);
    }
}
