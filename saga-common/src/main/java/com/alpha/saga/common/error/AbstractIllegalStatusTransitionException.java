package com.alpha.saga.common.error;

import java.util.Objects;

/**
 * Base for a service's "code attempted a state transition its own functional state machine does
 * not allow" exception (Order/Inventory/Payment each have one). An internal invariant violation
 * of that service — not a classified business error — so it extends {@link IllegalStateException}
 * and deliberately does <em>not</em> extend {@link SagaException}.
 *
 * <p>Not generic: the JLS forbids a generic subclass of {@link Throwable}, so {@link #from()}/
 * {@link #to()} are typed as {@code Enum<?>}. No production code reads them (they exist for
 * assertions); each concrete subclass still takes its own status enum in its constructor.
 */
public abstract class AbstractIllegalStatusTransitionException extends IllegalStateException {

    private final Enum<?> from;
    private final Enum<?> to;

    /**
     * @param aggregate the aggregate whose state machine was violated (e.g. {@code "order"}), used only in the message
     * @param from      the current status; must not be null
     * @param to        the rejected target status; must not be null
     */
    protected AbstractIllegalStatusTransitionException(final String aggregate,
            final Enum<?> from,
            final Enum<?> to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        super("Illegal %s transition from %s to %s".formatted(aggregate, from, to));
        this.from = from;
        this.to = to;
    }

    public Enum<?> from() {
        return this.from;
    }

    public Enum<?> to() {
        return this.to;
    }
}
