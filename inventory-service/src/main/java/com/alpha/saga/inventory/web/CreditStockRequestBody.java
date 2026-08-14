package com.alpha.saga.inventory.web;

import com.alpha.saga.common.util.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;
import java.util.UUID;

/** Request body for {@code POST /inventory/reservations/credit}, an order-adjustment saga's quantity-decrease step. */
public record CreditStockRequestBody(
        @Schema(description = "The adjustment's own saga id (not the original order's)", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sagaId,
        @Schema(description = "Stock keeping unit to credit back", example = "SKU-001", requiredMode = Schema.RequiredMode.REQUIRED)
        String sku,
        @Schema(description = "Quantity to credit back, must be positive", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer quantity) {

    public CreditStockRequestBody {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        if (StringUtils.isBlank(sku)) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
