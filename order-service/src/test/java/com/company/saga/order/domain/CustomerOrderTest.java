package com.company.saga.order.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerOrderTest {

    private static final String BUSINESS_KEY = "ORDER-2026-000001";

    @Test
    void createProducesAFreshUnpersistedOrderInPendingStatus() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = OrderTestClock.FIXED_INSTANT;

        final CustomerOrder order = CustomerOrder.create(sagaId, BUSINESS_KEY, now);

        assertThat(order.id()).isEqualTo(sagaId);
        assertThat(order.businessKey()).isEqualTo(BUSINESS_KEY);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.createdAt()).isEqualTo(now);
        assertThat(order.updatedAt()).isEqualTo(now);
        assertThat(order.version()).isNull();
    }

    @Test
    void confirmAdvancesFromPendingToConfirmed() {
        final Instant createdAt = OrderTestClock.FIXED_INSTANT;
        final Instant confirmedAt = Instant.parse("2026-08-03T09:05:00Z");
        final CustomerOrder pending = CustomerOrder.create(UUID.randomUUID(), BUSINESS_KEY, createdAt);

        final CustomerOrder confirmed = pending.confirm(confirmedAt);

        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.updatedAt()).isEqualTo(confirmedAt);
        assertThat(confirmed.id()).isEqualTo(pending.id());
        assertThat(confirmed.businessKey()).isEqualTo(pending.businessKey());
        assertThat(confirmed.createdAt()).isEqualTo(pending.createdAt());
        // The original instance is untouched (records are immutable).
        assertThat(pending.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void cancelAdvancesFromPendingToCancelled() {
        final Instant createdAt = OrderTestClock.FIXED_INSTANT;
        final Instant cancelledAt = Instant.parse("2026-08-03T09:05:00Z");
        final CustomerOrder pending = CustomerOrder.create(UUID.randomUUID(), BUSINESS_KEY, createdAt);

        final CustomerOrder cancelled = pending.cancel(cancelledAt);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.updatedAt()).isEqualTo(cancelledAt);
    }

    @Test
    void confirmingAnAlreadyConfirmedOrderThrows() {
        final Instant now = Instant.now();
        final CustomerOrder confirmed = CustomerOrder.create(UUID.randomUUID(), BUSINESS_KEY, now).confirm(now);

        assertThatThrownBy(() -> confirmed.confirm(now))
                .isInstanceOf(IllegalOrderTransitionException.class)
                .satisfies(exception -> {
                    final IllegalOrderTransitionException illegal = (IllegalOrderTransitionException) exception;
                    assertThat(illegal.from()).isEqualTo(OrderStatus.CONFIRMED);
                    assertThat(illegal.to()).isEqualTo(OrderStatus.CONFIRMED);
                });
    }

    @Test
    void cancellingAConfirmedOrderThrows() {
        final Instant now = Instant.now();
        final CustomerOrder confirmed = CustomerOrder.create(UUID.randomUUID(), BUSINESS_KEY, now).confirm(now);

        assertThatThrownBy(() -> confirmed.cancel(now))
                .isInstanceOf(IllegalOrderTransitionException.class)
                .satisfies(exception -> {
                    final IllegalOrderTransitionException illegal = (IllegalOrderTransitionException) exception;
                    assertThat(illegal.from()).isEqualTo(OrderStatus.CONFIRMED);
                    assertThat(illegal.to()).isEqualTo(OrderStatus.CANCELLED);
                });
    }

    @Test
    void rejectsBlankBusinessKey() {
        final UUID id = UUID.randomUUID();
        final Instant now = Instant.now();

        assertThatThrownBy(() -> new CustomerOrder(id, " ", OrderStatus.PENDING, now, now, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("businessKey");
    }

    @Test
    void rejectsRequiredNulls() {
        final UUID id = UUID.randomUUID();
        final Instant now = Instant.now();

        assertThatThrownBy(() -> new CustomerOrder(null, "BK-1", OrderStatus.PENDING, now, now, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CustomerOrder(id, null, OrderStatus.PENDING, now, now, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CustomerOrder(id, "BK-1", null, now, now, null))
                .isInstanceOf(NullPointerException.class);
    }
}
