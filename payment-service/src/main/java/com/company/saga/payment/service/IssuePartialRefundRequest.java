package com.company.saga.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input to {@link PaymentProgressionService#issuePartialRefund(IssuePartialRefundRequest)}. */
public record IssuePartialRefundRequest(UUID sagaId,
        BigDecimal amount,
        Instant now) {

    public IssuePartialRefundRequest {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
