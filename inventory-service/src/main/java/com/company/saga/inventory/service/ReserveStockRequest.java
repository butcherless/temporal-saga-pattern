package com.company.saga.inventory.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input to {@link InventoryProgressionService#reserveStock(ReserveStockRequest)}. */
public record ReserveStockRequest(UUID sagaId, String sku, Integer quantity, Instant now) {

    public ReserveStockRequest {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
