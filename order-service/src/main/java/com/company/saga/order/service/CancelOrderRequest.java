package com.company.saga.order.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input to {@link OrderProgressionService#cancelOrder(CancelOrderRequest)}. */
public record CancelOrderRequest(UUID sagaId,
        Instant now) {

    public CancelOrderRequest {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(now, "now must not be null");
    }
}
