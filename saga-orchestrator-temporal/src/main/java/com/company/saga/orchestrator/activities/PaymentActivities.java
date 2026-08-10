package com.company.saga.orchestrator.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.math.BigDecimal;
import java.util.UUID;

/** Activities calling {@code payment-service}'s REST endpoints directly (no Kafka involved). */
@ActivityInterface
public interface PaymentActivities {

    @ActivityMethod
    void requestPayment(UUID sagaId, BigDecimal amount);
}
