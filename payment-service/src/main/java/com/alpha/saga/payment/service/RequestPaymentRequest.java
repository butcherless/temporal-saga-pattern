package com.alpha.saga.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input to {@link PaymentProgressionService#requestPayment(RequestPaymentRequest)}. */
public record RequestPaymentRequest(UUID sagaId,
        BigDecimal amount,
        Instant now) {

    public RequestPaymentRequest {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
