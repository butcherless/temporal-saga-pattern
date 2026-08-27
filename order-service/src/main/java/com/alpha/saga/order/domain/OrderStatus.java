package com.alpha.saga.order.domain;

import com.alpha.saga.common.state.StateTransitions;

import java.util.Map;
import java.util.Set;

/**
 * The order's own functional state machine (proposal, section 6.1), simplified to the states
 * actually driven by a command (proposal §13.1): no command or reference flow (§8) ever asks
 * Order Service to mark {@code PENDING_INVENTORY}/{@code PENDING_PAYMENT} — that progress is
 * tracked by {@code SagaStatus} on the orchestrator side instead. {@code CONFIRMED} is a
 * {@code PIVOT} operation (proposal §9.3): once reached, it has no automatic reversal.
 */
public enum OrderStatus {

    PENDING,
    CONFIRMED,
    CANCELLED;

    private static final StateTransitions<OrderStatus> TRANSITIONS = StateTransitions.of(Map.of(
            PENDING, Set.of(CONFIRMED, CANCELLED),
            CONFIRMED, Set.of(),
            CANCELLED, Set.of()));

    /** Whether this state may legally transition directly to {@code target}. */
    public boolean canTransitionTo(final OrderStatus target) {
        return TRANSITIONS.allows(this, target);
    }

    /** The set of states this state may legally transition to; empty for terminal states. */
    public Set<OrderStatus> allowedNextStates() {
        return TRANSITIONS.nextStates(this);
    }

    /** Whether no further transition is possible from this state. */
    public boolean isTerminal() {
        return TRANSITIONS.isTerminal(this);
    }
}
