package com.alpha.saga.inventory.service;

import com.alpha.saga.common.util.StringUtils;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input to {@link InventoryProgressionService#creditStock(CreditStockRequest)}. */
public record CreditStockRequest(UUID sagaId,
        String sku,
        Integer quantity,
        Instant now) {

    public CreditStockRequest {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (StringUtils.isBlank(sku)) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
