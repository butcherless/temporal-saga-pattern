package com.company.saga.order.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
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

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = buildTransitionGraph();

    /** Whether this state may legally transition directly to {@code target}. */
    public boolean canTransitionTo(final OrderStatus target) {
        Objects.requireNonNull(target, "target must not be null");
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    /** The set of states this state may legally transition to; empty for terminal states. */
    public Set<OrderStatus> allowedNextStates() {
        return ALLOWED_TRANSITIONS.get(this);
    }

    /** Whether no further transition is possible from this state. */
    public boolean isTerminal() {
        return allowedNextStates().isEmpty();
    }

    private static Map<OrderStatus, Set<OrderStatus>> buildTransitionGraph() {
        final Map<OrderStatus, Set<OrderStatus>> graph = new EnumMap<>(OrderStatus.class);

        graph.put(PENDING, Set.of(CONFIRMED, CANCELLED));
        graph.put(CONFIRMED, Set.of());
        graph.put(CANCELLED, Set.of());

        return Collections.unmodifiableMap(graph);
    }
}
