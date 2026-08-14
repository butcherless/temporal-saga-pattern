package com.alpha.saga.inventory.domain;

import java.time.Instant;

/** Fixed instant reused across inventory-service tests wherever "some representative timestamp" is all that's needed. */
public final class InventoryTestClock {

    public static final Instant FIXED_INSTANT = Instant.parse("2026-08-03T09:00:00Z");

    private InventoryTestClock() {
    }
}
