package com.company.saga.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.UUID;

/** Activities calling {@code order-service}'s REST endpoints directly (no Kafka involved). */
@ActivityInterface
public interface OrderActivities {

    @ActivityMethod
    void confirmOrder(UUID sagaId);
}
