package com.alpha.saga.payment.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
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

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = buildTransitionGraph();

    /** Whether this state may legally transition directly to {@code target}. */
    public boolean canTransitionTo(final PaymentStatus target) {
        Objects.requireNonNull(target, "target must not be null");
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** The set of states this state may legally transition to; empty for terminal states. */
    public Set<PaymentStatus> allowedNextStates() {
        return ALLOWED_TRANSITIONS.get(this);
    }

    /** Whether no further transition is possible from this state. */
    public boolean isTerminal() {
        return allowedNextStates().isEmpty();
    }

    private static Map<PaymentStatus, Set<PaymentStatus>> buildTransitionGraph() {
        final Map<PaymentStatus, Set<PaymentStatus>> graph = new EnumMap<>(PaymentStatus.class);

        graph.put(PENDING, Set.of(COMPLETED, FAILED));
        graph.put(COMPLETED, Set.of(REFUNDED));
        graph.put(FAILED, Set.of());
        graph.put(REFUNDED, Set.of());

        return Collections.unmodifiableMap(graph);
    }
}
