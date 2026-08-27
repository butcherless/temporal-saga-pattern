package com.alpha.saga.order.web;

import com.alpha.saga.common.util.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Request body for {@code POST /orders} (Step 6 plan, decision 7): {@code businessKey} is the
 * client's own idempotency key and is optional — {@link OrderCreationHandler} generates one from
 * the minted {@code sagaId} when omitted or blank.
 */
public record CreateOrderRequestBody(
        @Schema(description = "Stock keeping unit to order", example = "SKU-001", requiredMode = Schema.RequiredMode.REQUIRED)
        String sku,
        @Schema(description = "Quantity to order, must be positive", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer quantity,
        @Schema(description = "Total order amount, must be positive", example = "49.99", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal amount,
        @Schema(description = "Client-supplied idempotency key; generated from the minted sagaId when omitted", example = "ORDER-2026-000001")
        String businessKey) {

    public CreateOrderRequestBody {
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (StringUtils.isBlank(sku)) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
