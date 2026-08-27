package com.alpha.saga.common.state;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable directed graph of the legal transitions between the constants of one status enum,
 * shared by every service's functional state machine (Order/Inventory/Payment) so each enum only
 * declares its own edges instead of re-implementing the same lookup/terminal boilerplate.
 *
 * <p>Hold one as a {@code private static final} field on the enum, built from a literal map:
 * <pre>{@code
 * private static final StateTransitions<OrderStatus> TRANSITIONS = StateTransitions.of(Map.of(
 *         PENDING,   Set.of(CONFIRMED, CANCELLED),
 *         CONFIRMED, Set.of(),
 *         CANCELLED, Set.of()));
 * }</pre>
 *
 * @param <S> the status enum this graph is over
 */
public final class StateTransitions<S extends Enum<S>> {

    private final Map<S, Set<S>> graph;

    private StateTransitions(final Map<S, Set<S>> graph) {
        this.graph = graph;
    }

    /** Builds a graph from {@code graph}; every enum constant should appear as a key (terminal states map to an empty set). */
    public static <S extends Enum<S>> StateTransitions<S> of(final Map<S, Set<S>> graph) {
        Objects.requireNonNull(graph, "graph must not be null");
        return new StateTransitions<>(Map.copyOf(graph));
    }

    /** Whether {@code from} may legally transition directly to {@code to}. */
    public boolean allows(final S from,
            final S to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        return this.nextStates(from).contains(to);
    }

    /** The states {@code from} may legally transition to; empty for terminal states. */
    public Set<S> nextStates(final S from) {
        Objects.requireNonNull(from, "from must not be null");
        return this.graph.getOrDefault(from, Set.of());
    }

    /** Whether no further transition is possible from {@code from}. */
    public boolean isTerminal(final S from) {
        return this.nextStates(from).isEmpty();
    }
}
