package com.alpha.saga.payment.domain;

import java.time.Instant;

/** Shared fixed instant for tests that need a deterministic {@code now} without caring about its exact value. */
public final class PaymentTestClock {

    public static final Instant FIXED_INSTANT = Instant.parse("2026-08-03T09:00:00Z");

    private PaymentTestClock() {
    }
}
