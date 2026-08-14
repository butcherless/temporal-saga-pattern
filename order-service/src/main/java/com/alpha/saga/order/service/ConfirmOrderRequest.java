package com.alpha.saga.order.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input to {@link OrderProgressionService#confirmOrder(ConfirmOrderRequest)}. */
public record ConfirmOrderRequest(UUID sagaId,
        Instant now) {

    public ConfirmOrderRequest {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(now, "now must not be null");
    }
}
