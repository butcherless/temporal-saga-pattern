package com.company.saga.payment.web;

import com.company.saga.payment.domain.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;
import java.util.UUID;

/** Response body for {@code POST /payments}: the payment's own id (the saga's id) and its status. */
public record PaymentResponseBody(
        @Schema(description = "The saga's own id — also the payment's id", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sagaId,
        @Schema(description = "The payment's status", requiredMode = Schema.RequiredMode.REQUIRED)
        PaymentStatus status) {

    public PaymentResponseBody {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
