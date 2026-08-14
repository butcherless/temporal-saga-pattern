package com.alpha.saga.payment.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input to {@link PaymentProgressionService#refundPayment(RefundPaymentRequest)}. */
public record RefundPaymentRequest(UUID sagaId,
        Instant now) {

    public RefundPaymentRequest {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(now, "now must not be null");
    }
}
