package com.alpha.saga.order.domain;

import com.alpha.saga.common.error.AbstractIllegalStatusTransitionException;

/**
 * Thrown when code attempts to move an order to an {@link OrderStatus} its current status does
 * not allow. See {@link AbstractIllegalStatusTransitionException} for why this is an internal
 * invariant violation rather than a classified {@code SagaException}.
 */
public final class IllegalOrderTransitionException extends AbstractIllegalStatusTransitionException {

    public IllegalOrderTransitionException(final OrderStatus from,
            final OrderStatus to) {
        super("order", from, to);
    }
}
