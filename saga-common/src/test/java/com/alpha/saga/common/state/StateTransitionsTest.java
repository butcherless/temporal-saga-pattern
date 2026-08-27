package com.alpha.saga.common.state;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.alpha.saga.common.state.StateTransitionsTest.Signal.GREEN;
import static com.alpha.saga.common.state.StateTransitionsTest.Signal.RED;
import static com.alpha.saga.common.state.StateTransitionsTest.Signal.YELLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateTransitionsTest {

    enum Signal {
        RED,
        GREEN,
        YELLOW
    }

    private static final StateTransitions<Signal> TRANSITIONS = StateTransitions.of(Map.of(
            RED, Set.of(GREEN),
            GREEN, Set.of(YELLOW),
            YELLOW, Set.of()));

    @Test
    void allowsOnlyTheEdgesDeclaredInTheGraph() {
        assertThat(TRANSITIONS.allows(RED, GREEN)).isTrue();
        assertThat(TRANSITIONS.allows(GREEN, YELLOW)).isTrue();
        assertThat(TRANSITIONS.allows(RED, YELLOW)).isFalse();
        assertThat(TRANSITIONS.allows(YELLOW, RED)).isFalse();
    }

    @Test
    void allowsRejectsNullArguments() {
        assertThatThrownBy(() -> TRANSITIONS.allows(null, GREEN)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TRANSITIONS.allows(RED, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nextStatesReturnsTheOutgoingEdges() {
        assertThat(TRANSITIONS.nextStates(RED)).containsExactly(GREEN);
        assertThat(TRANSITIONS.nextStates(YELLOW)).isEmpty();
    }

    @Test
    void nextStatesRejectsNull() {
        assertThatThrownBy(() -> TRANSITIONS.nextStates(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void isTerminalOnlyWhenThereAreNoOutgoingEdges() {
        assertThat(TRANSITIONS.isTerminal(YELLOW)).isTrue();
        assertThat(TRANSITIONS.isTerminal(RED)).isFalse();
    }

    @Test
    void ofRejectsANullGraph() {
        assertThatThrownBy(() -> StateTransitions.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofCopiesTheGraphDefensively() {
        final Map<Signal, Set<Signal>> mutable = new HashMap<>();
        mutable.put(RED, Set.of(GREEN));
        final StateTransitions<Signal> snapshot = StateTransitions.of(mutable);

        mutable.clear();

        assertThat(snapshot.allows(RED, GREEN)).isTrue();
    }

    @Test
    void treatsAStateAbsentFromTheGraphAsTerminal() {
        final StateTransitions<Signal> partial = StateTransitions.of(Map.of(RED, Set.of(GREEN)));

        assertThat(partial.isTerminal(YELLOW)).isTrue();
        assertThat(partial.nextStates(YELLOW)).isEmpty();
        assertThat(partial.allows(YELLOW, RED)).isFalse();
    }
}
