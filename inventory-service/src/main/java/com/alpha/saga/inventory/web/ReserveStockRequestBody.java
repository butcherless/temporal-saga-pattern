package com.alpha.saga.inventory.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;
import java.util.UUID;

/** Request body for {@code POST /inventory/reservations}, called by the order saga Workflow's InventoryActivities. */
public record ReserveStockRequestBody(
        @Schema(description = "The saga this reservation belongs to; also the reservation's own id", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sagaId,
        @Schema(description = "Stock keeping unit to reserve", example = "SKU-001", requiredMode = Schema.RequiredMode.REQUIRED)
        String sku,
        @Schema(description = "Quantity to reserve, must be positive", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer quantity) {

    public ReserveStockRequestBody {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
