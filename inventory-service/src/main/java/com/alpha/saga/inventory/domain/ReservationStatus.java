package com.alpha.saga.inventory.domain;

import com.alpha.saga.common.state.StateTransitions;

import java.util.Map;
import java.util.Set;

/**
 * An inventory reservation's own functional state machine (proposal, section 6.2). A reservation
 * only comes into existence once stock has actually been debited (mirrors {@code OrderStatus}:
 * no separate "requested" state — that progress is tracked by {@code SagaStatus} on the
 * orchestrator side instead). {@code CONFIRMED} is a {@code PIVOT} operation (proposal §9.3):
 * once reached, it has no automatic reversal.
 */
public enum ReservationStatus {

    RESERVED,
    CONFIRMED,
    RELEASED;

    private static final StateTransitions<ReservationStatus> TRANSITIONS = StateTransitions.of(Map.of(
            RESERVED, Set.of(CONFIRMED, RELEASED),
            CONFIRMED, Set.of(),
            RELEASED, Set.of()));

    /** Whether this state may legally transition directly to {@code target}. */
    public boolean canTransitionTo(final ReservationStatus target) {
        return TRANSITIONS.allows(this, target);
    }

    /** The set of states this state may legally transition to; empty for terminal states. */
    public Set<ReservationStatus> allowedNextStates() {
        return TRANSITIONS.nextStates(this);
    }

    /** Whether no further transition is possible from this state. */
    public boolean isTerminal() {
        return TRANSITIONS.isTerminal(this);
    }
}
