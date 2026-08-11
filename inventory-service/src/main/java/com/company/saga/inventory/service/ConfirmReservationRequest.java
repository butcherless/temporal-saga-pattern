package com.company.saga.inventory.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Input to {@link InventoryProgressionService#confirmReservation(ConfirmReservationRequest)}. */
public record ConfirmReservationRequest(UUID sagaId,
        Instant now) {

    public ConfirmReservationRequest {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(now, "now must not be null");
    }
}
