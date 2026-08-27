package com.alpha.saga.payment.domain;

import com.alpha.saga.common.error.AbstractIllegalStatusTransitionException;

/**
 * Thrown when code attempts to move a payment to a {@link PaymentStatus} its current status does
 * not allow. See {@link AbstractIllegalStatusTransitionException} for why this is an internal
 * invariant violation rather than a classified {@code SagaException}.
 */
public final class IllegalPaymentTransitionException extends AbstractIllegalStatusTransitionException {

    public IllegalPaymentTransitionException(final PaymentStatus from,
            final PaymentStatus to) {
        super("payment", from, to);
    }
}
