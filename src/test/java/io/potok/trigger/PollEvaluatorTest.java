package io.potok.trigger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PollEvaluatorTest {

    @Test
    void changedModeNeverFiresOnFirstPoll() {
        assertThat(PollEvaluator.changed(null, "h1").fire()).isFalse();
    }

    @Test
    void changedModeFiresOnlyOnDifferentHash() {
        assertThat(PollEvaluator.changed("h1", "h1").fire()).isFalse();
        assertThat(PollEvaluator.changed("h1", "h2").fire()).isTrue();
    }

    @Test
    void expressionFiresOnFalseToTrueOnly() {
        // first poll: no previous value — record, don't fire even when true
        assertThat(PollEvaluator.expression(null, true, "h").fire()).isFalse();
        assertThat(PollEvaluator.expression(null, false, "h").fire()).isFalse();
        // edge
        assertThat(PollEvaluator.expression(false, true, "h").fire()).isTrue();
        // level — stays true, no refire
        assertThat(PollEvaluator.expression(true, true, "h").fire()).isFalse();
        // falling edge
        assertThat(PollEvaluator.expression(true, false, "h").fire()).isFalse();
    }

    @Test
    void decisionCarriesNewState() {
        var d = PollEvaluator.expression(false, true, "hash9");
        assertThat(d.newHash()).isEqualTo("hash9");
        assertThat(d.newCondition()).isTrue();
    }
}
