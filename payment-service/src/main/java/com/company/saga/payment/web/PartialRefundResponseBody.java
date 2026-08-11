package com.company.saga.payment.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Response body for {@code POST /payments/partial-refunds}: the refund's own id (the adjustment saga's id) and its amount. */
public record PartialRefundResponseBody(
        @Schema(description = "The adjustment saga's own id — also the refund's id", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sagaId,
        @Schema(description = "The refunded amount", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal amount) {

    public PartialRefundResponseBody {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }
}
