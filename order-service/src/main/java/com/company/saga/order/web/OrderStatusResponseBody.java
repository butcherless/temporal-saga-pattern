package com.company.saga.order.web;

import com.company.saga.common.workflow.OrderSagaProgress;
import com.company.saga.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;
import java.util.UUID;

/** Response body for {@code GET /orders/{sagaId}}. */
public record OrderStatusResponseBody(
        @Schema(description = "The Saga's own id — also the order's id", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sagaId,
        @Schema(description = "The order's own durable status", requiredMode = Schema.RequiredMode.REQUIRED)
        OrderStatus orderStatus,
        @Schema(description = "The saga's live progress — read from the Workflow while still PENDING, "
                + "derived from orderStatus once terminal", requiredMode = Schema.RequiredMode.REQUIRED)
        OrderSagaProgress sagaProgress) {

    public OrderStatusResponseBody {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(orderStatus, "orderStatus must not be null");
        Objects.requireNonNull(sagaProgress, "sagaProgress must not be null");
    }
}
