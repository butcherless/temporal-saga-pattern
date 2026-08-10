package com.company.saga.inventory.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
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

    private static final Map<ReservationStatus, Set<ReservationStatus>> ALLOWED_TRANSITIONS = buildTransitionGraph();

    /** Whether this state may legally transition directly to {@code target}. */
    public boolean canTransitionTo(final ReservationStatus target) {
        Objects.requireNonNull(target, "target must not be null");
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** The set of states this state may legally transition to; empty for terminal states. */
    public Set<ReservationStatus> allowedNextStates() {
        return ALLOWED_TRANSITIONS.get(this);
    }

    /** Whether no further transition is possible from this state. */
    public boolean isTerminal() {
        return allowedNextStates().isEmpty();
    }

    private static Map<ReservationStatus, Set<ReservationStatus>> buildTransitionGraph() {
        final Map<ReservationStatus, Set<ReservationStatus>> graph = new EnumMap<>(ReservationStatus.class);

        graph.put(RESERVED, Set.of(CONFIRMED, RELEASED));
        graph.put(CONFIRMED, Set.of());
        graph.put(RELEASED, Set.of());

        return Collections.unmodifiableMap(graph);
    }
}
