package com.alpha.saga.inventory.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.alpha.saga.inventory.domain.InventoryTestClock.FIXED_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryReservationTest {

    private static final String SKU = "SKU-001";

    @Test
    void reserveProducesAFreshReservationInReservedStatus() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = FIXED_INSTANT;

        final InventoryReservation reservation = InventoryReservation.reserve(sagaId, SKU, 5, now);

        assertThat(reservation.id()).isEqualTo(sagaId);
        assertThat(reservation.sku()).isEqualTo(SKU);
        assertThat(reservation.quantity()).isEqualTo(5);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.createdAt()).isEqualTo(now);
        assertThat(reservation.updatedAt()).isEqualTo(now);
        assertThat(reservation.version()).isNull();
    }

    @Test
    void confirmAdvancesFromReservedToConfirmed() {
        final Instant createdAt = FIXED_INSTANT;
        final Instant confirmedAt = Instant.parse("2026-08-03T09:05:00Z");
        final InventoryReservation reserved = InventoryReservation.reserve(UUID.randomUUID(), SKU, 5, createdAt);

        final InventoryReservation confirmed = reserved.confirm(confirmedAt);

        assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(confirmed.updatedAt()).isEqualTo(confirmedAt);
        // The original instance is untouched (records are immutable).
        assertThat(reserved.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void releaseAdvancesFromReservedToReleased() {
        final Instant createdAt = FIXED_INSTANT;
        final Instant releasedAt = Instant.parse("2026-08-03T09:05:00Z");
        final InventoryReservation reserved = InventoryReservation.reserve(UUID.randomUUID(), SKU, 5, createdAt);

        final InventoryReservation released = reserved.release(releasedAt);

        assertThat(released.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(released.updatedAt()).isEqualTo(releasedAt);
    }

    @Test
    void confirmingAnAlreadyConfirmedReservationThrows() {
        final Instant now = Instant.now();
        final InventoryReservation confirmed = InventoryReservation.reserve(UUID.randomUUID(), SKU, 5, now).confirm(now);

        assertThatThrownBy(() -> confirmed.confirm(now))
                .isInstanceOf(IllegalReservationTransitionException.class)
                .satisfies(exception -> {
                    final IllegalReservationTransitionException illegal = (IllegalReservationTransitionException) exception;
                    assertThat(illegal.from()).isEqualTo(ReservationStatus.CONFIRMED);
                    assertThat(illegal.to()).isEqualTo(ReservationStatus.CONFIRMED);
                });
    }

    @Test
    void releasingAConfirmedReservationThrows() {
        final Instant now = Instant.now();
        final InventoryReservation confirmed = InventoryReservation.reserve(UUID.randomUUID(), SKU, 5, now).confirm(now);

        assertThatThrownBy(() -> confirmed.release(now))
                .isInstanceOf(IllegalReservationTransitionException.class)
                .satisfies(exception -> {
                    final IllegalReservationTransitionException illegal = (IllegalReservationTransitionException) exception;
                    assertThat(illegal.from()).isEqualTo(ReservationStatus.CONFIRMED);
                    assertThat(illegal.to()).isEqualTo(ReservationStatus.RELEASED);
                });
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> InventoryReservation.reserve(UUID.randomUUID(), SKU, 0, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void rejectsBlankSku() {
        assertThatThrownBy(() -> InventoryReservation.reserve(UUID.randomUUID(), " ", 5, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku");
    }

    @Test
    void rejectsRequiredNulls() {
        final UUID id = UUID.randomUUID();
        final Instant now = Instant.now();

        assertThatThrownBy(() -> new InventoryReservation(null, SKU, 1, ReservationStatus.RESERVED, now, now, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new InventoryReservation(id, null, 1, ReservationStatus.RESERVED, now, now, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new InventoryReservation(id, SKU, 1, null, now, now, null))
                .isInstanceOf(NullPointerException.class);
    }
}
