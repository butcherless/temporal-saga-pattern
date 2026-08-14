package com.alpha.saga.order.web;

import com.alpha.saga.common.util.StringUtils;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;
import java.util.UUID;

/** Response body for {@code POST /orders}: the persisted order's own id (the Saga's id) and its idempotency key. */
public record CreateOrderResponseBody(
        @Schema(description = "The Saga's own id — also the persisted order's id", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sagaId,
        @Schema(description = "The order's idempotency key (client-supplied or generated)", requiredMode = Schema.RequiredMode.REQUIRED)
        String businessKey) {

    public CreateOrderResponseBody {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(businessKey, "businessKey must not be null");
        if (StringUtils.isBlank(businessKey)) {
            throw new IllegalArgumentException("businessKey must not be blank");
        }
    }
}
