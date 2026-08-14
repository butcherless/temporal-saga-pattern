package com.alpha.saga.payment.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Request body for {@code POST /payments/partial-refunds}, an order-adjustment saga's quantity-decrease step. */
public record IssuePartialRefundRequestBody(
        @Schema(description = "The adjustment's own saga id (not the original order's)", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sagaId,
        @Schema(description = "Amount to refund, must be positive", example = "20.00", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal amount) {

    public IssuePartialRefundRequestBody {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
