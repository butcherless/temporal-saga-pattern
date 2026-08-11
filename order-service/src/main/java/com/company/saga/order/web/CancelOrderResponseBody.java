package com.company.saga.order.web;

import com.company.saga.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;
import java.util.UUID;

/** Response body for {@code POST /orders/{sagaId}/cancel}. */
public record CancelOrderResponseBody(
        @Schema(description = "The Saga's own id — also the cancelled order's id", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sagaId,
        @Schema(description = "The order's status after cancellation", requiredMode = Schema.RequiredMode.REQUIRED)
        OrderStatus status) {

    public CancelOrderResponseBody {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
