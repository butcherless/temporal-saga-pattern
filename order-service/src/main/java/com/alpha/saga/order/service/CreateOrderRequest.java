package com.alpha.saga.order.service;

import com.alpha.saga.common.util.StringUtils;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input to {@link OrderProgressionService#createOrder(CreateOrderRequest)}. */
public record CreateOrderRequest(UUID sagaId,
        String businessKey,
        Instant now) {

    public CreateOrderRequest {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(businessKey, "businessKey must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (StringUtils.isBlank(businessKey)) {
            throw new IllegalArgumentException("businessKey must not be blank");
        }
    }
}
