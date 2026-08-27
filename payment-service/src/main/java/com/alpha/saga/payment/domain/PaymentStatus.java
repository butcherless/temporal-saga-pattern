package com.alpha.saga.payment.domain;

import com.alpha.saga.common.state.StateTransitions;

import java.util.Map;
import java.util.Set;

/**
 * A payment's own functional state machine (proposal, section 6.3), simplified per the Step 5
 * plan (decision 2): {@code AUTHORIZED}/{@code CAPTURED} collapse into one {@code COMPLETED}
 * step, and there is no separate {@code confirmPayment} — no reference flow (proposal §8) ever
 * calls {@code ConfirmPaymentCommand}. {@code COMPLETED} is the only non-terminal state that
 * survives past a single request/response, since a refund can still follow it.
 */
public enum PaymentStatus {

    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED;

    private static final StateTransitions<PaymentStatus> TRANSITIONS = StateTransitions.of(Map.of(
            PENDING, Set.of(COMPLETED, FAILED),
            COMPLETED, Set.of(REFUNDED),
            FAILED, Set.of(),
            REFUNDED, Set.of()));

    /** Whether this state may legally transition directly to {@code target}. */
    public boolean canTransitionTo(final PaymentStatus target) {
        return TRANSITIONS.allows(this, target);
    }

    /** The set of states this state may legally transition to; empty for terminal states. */
    public Set<PaymentStatus> allowedNextStates() {
        return TRANSITIONS.nextStates(this);
    }

    /** Whether no further transition is possible from this state. */
    public boolean isTerminal() {
        return TRANSITIONS.isTerminal(this);
    }
}
