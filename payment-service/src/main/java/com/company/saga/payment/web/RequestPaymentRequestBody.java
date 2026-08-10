package com.company.saga.payment.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Request body for {@code POST /payments}, called by the order saga Workflow's PaymentActivities. */
public record RequestPaymentRequestBody(
        @Schema(description = "The saga this payment belongs to; also the payment's own id", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sagaId,
        @Schema(description = "Amount to charge, must be positive", example = "49.99", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal amount) {

    public RequestPaymentRequestBody {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
