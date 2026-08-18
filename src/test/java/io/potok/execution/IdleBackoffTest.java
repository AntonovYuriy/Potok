package io.potok.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class IdleBackoffTest {

    @Test
    void growsFromBaseByDoublingUpToCap() {
        IdleBackoff backoff = new IdleBackoff(Duration.ofSeconds(10), Duration.ofSeconds(30));

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(20));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.nextDelay()).as("stays at the cap").isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void resetReturnsToBase() {
        IdleBackoff backoff = new IdleBackoff(Duration.ofSeconds(1), Duration.ofSeconds(8));
        backoff.nextDelay();
        backoff.nextDelay();

        backoff.reset();

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void capBelowBaseIsClampedToBase() {
        IdleBackoff backoff = new IdleBackoff(Duration.ofSeconds(10), Duration.ofSeconds(5));

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(backoff.nextDelay()).as("never sleeps longer than the (clamped) cap")
                .isEqualTo(Duration.ofSeconds(10));
    }
}
