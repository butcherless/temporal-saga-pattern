package com.alpha.saga.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.UUID;

/** Activities calling {@code order-service}'s REST endpoints directly (no Kafka involved). */
@ActivityInterface
public interface OrderActivities {

    @ActivityMethod
    void confirmOrder(UUID sagaId);

    /** Compensation: cancels the order when a later saga step permanently fails. */
    @ActivityMethod
    void cancelOrder(UUID sagaId);
}
