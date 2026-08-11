package com.company.saga.orchestrator.activities;

import java.util.UUID;

/** Body sent to {@code inventory-service}'s {@code POST /inventory/reservations}. */
record InventoryReservationRequest(UUID sagaId,
        String sku,
        Integer quantity) {
}
