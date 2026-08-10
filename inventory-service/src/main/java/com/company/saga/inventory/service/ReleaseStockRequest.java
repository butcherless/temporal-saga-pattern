package com.company.saga.inventory.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input to {@link InventoryProgressionService#releaseStock(ReleaseStockRequest)}. */
public record ReleaseStockRequest(UUID sagaId, Instant now) {

    public ReleaseStockRequest {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(now, "now must not be null");
    }
}
