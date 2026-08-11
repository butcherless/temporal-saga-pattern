package com.company.saga.orchestrator.activities;

import java.math.BigDecimal;
import java.util.UUID;

/** Body sent to {@code payment-service}'s {@code POST /payments}. */
record PaymentChargeRequest(UUID sagaId,
        BigDecimal amount) {
}
