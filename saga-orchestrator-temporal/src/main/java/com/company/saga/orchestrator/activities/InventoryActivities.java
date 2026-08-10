package com.company.saga.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.UUID;

/** Activities calling {@code inventory-service}'s REST endpoints directly (no Kafka involved). */
@ActivityInterface
public interface InventoryActivities {

    @ActivityMethod
    void reserveStock(UUID sagaId, String sku, Integer quantity);

    @ActivityMethod
    void confirmReservation(UUID sagaId);
}
