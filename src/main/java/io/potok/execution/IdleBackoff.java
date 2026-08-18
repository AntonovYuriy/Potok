package io.potok.execution;

import java.time.Duration;

/**
 * Per-worker sleep policy for the queue poll loop: every consecutive empty
 * poll doubles the sleep from the base interval up to the cap; the moment a
 * job is found the delay resets to the base. A truly idle instance settles at
 * the cap (few statements per minute) while a busy one keeps the base
 * responsiveness. Worst-case pickup latency for a job enqueued while idle is
 * one cap interval.
 */
final class IdleBackoff {

    private final Duration base;
    private final Duration cap;
    private Duration current;

    IdleBackoff(Duration base, Duration cap) {
        this.base = base;
        this.cap = cap.compareTo(base) < 0 ? base : cap;
        this.current = base;
    }

    /** Delay to sleep after an empty poll; each call grows the next one up to the cap. */
    Duration nextDelay() {
        Duration delay = current;
        Duration doubled = current.multipliedBy(2);
        current = doubled.compareTo(cap) > 0 ? cap : doubled;
        return delay;
    }

    /** Work was found — the queue is live, poll at the base interval again. */
    void reset() {
        current = base;
    }
}
