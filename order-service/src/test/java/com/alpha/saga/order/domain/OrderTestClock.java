package com.alpha.saga.order.domain;

import java.time.Instant;

/** Fixed instant reused across order-service tests wherever "some representative timestamp" is all that's needed. */
public final class OrderTestClock {

    public static final Instant FIXED_INSTANT = Instant.parse("2026-08-03T09:00:00Z");

    private OrderTestClock() {
    }
}
