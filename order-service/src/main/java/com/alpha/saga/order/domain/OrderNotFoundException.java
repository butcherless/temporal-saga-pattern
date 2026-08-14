package com.alpha.saga.order.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Thrown when no order exists for a given {@code sagaId}. Unlike
 * {@link IllegalOrderTransitionException}, this isn't an internal invariant violation — it's a
 * legitimate outcome of a caller-supplied {@code sagaId} in {@code GET /orders/{sagaId}}, so
 * {@code RestExceptionHandler} maps it explicitly to 404 instead of letting it fall through.
 */
public final class OrderNotFoundException extends RuntimeException {

    private final UUID sagaId;

    public OrderNotFoundException(final UUID sagaId) {
        super("Order not found: %s".formatted(sagaId));
        this.sagaId = Objects.requireNonNull(sagaId, "sagaId must not be null");
    }

    public UUID sagaId() {
        return sagaId;
    }
}
