package com.alpha.saga.order.web;

import com.alpha.saga.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;
import java.util.UUID;

/** Response body for {@code POST /orders/{sagaId}/confirm}. */
public record ConfirmOrderResponseBody(
        @Schema(description = "The Saga's own id — also the confirmed order's id", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sagaId,
        @Schema(description = "The order's status after confirmation", requiredMode = Schema.RequiredMode.REQUIRED)
        OrderStatus status) {

    public ConfirmOrderResponseBody {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
