package com.company.saga.payment.domain;

import java.util.Objects;

/**
 * Thrown when code attempts to move a payment to a {@link PaymentStatus} its current status does
 * not allow. This is an internal invariant violation of Payment Service itself — not a
 * classified business error — so it does not extend {@code SagaException} from
 * {@code saga-common}.
 */
public final class IllegalPaymentTransitionException extends IllegalStateException {

    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalPaymentTransitionException(final PaymentStatus from,
            final PaymentStatus to) {
        super("Illegal payment transition from %s to %s".formatted(from, to));
        this.from = Objects.requireNonNull(from, "from must not be null");
        this.to = Objects.requireNonNull(to, "to must not be null");
    }

    public PaymentStatus from() {
        return from;
    }

    public PaymentStatus to() {
        return to;
    }
}
