package com.alpha.saga.order.domain;

import java.util.Objects;

/**
 * Thrown when code attempts to move an order to an {@link OrderStatus} its current status does
 * not allow. This is an internal invariant violation of Order Service itself — not a classified
 * business error — so it does not extend {@code SagaException} from {@code saga-common}.
 */
public final class IllegalOrderTransitionException extends IllegalStateException {

    private final OrderStatus from;
    private final OrderStatus to;

    public IllegalOrderTransitionException(final OrderStatus from,
            final OrderStatus to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        super("Illegal order transition from %s to %s".formatted(from, to));
        this.from = from;
        this.to = to;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }
}
