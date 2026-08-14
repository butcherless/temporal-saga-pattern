package com.alpha.saga.inventory.domain;

import com.alpha.saga.common.util.StringUtils;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Inventory Service's own aggregate (proposal, section 6.2): the reservation behind one Saga.
 * Its {@code id} is the Saga's own id (Step 5 plan, decision 6) — this PoC has exactly one
 * reservation per saga, so a separate surrogate id would be an unused column. Unlike
 * {@link StockItem}, this row does not carry the actual stock count — it only records what was
 * reserved and its own lifecycle.
 */
@Table("inventory_reservation")
public record InventoryReservation(
        @Id UUID id,
        String sku,
        int quantity,
        ReservationStatus status,
        Instant createdAt,
        Instant updatedAt,
        @Version Long version) {

    public InventoryReservation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (StringUtils.isBlank(sku)) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    /** Creates a brand-new reservation, in {@link ReservationStatus#RESERVED}. */
    public static InventoryReservation reserve(final UUID sagaId,
            final String sku,
            final int quantity,
            final Instant now) {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        return new InventoryReservation(sagaId, sku, quantity, ReservationStatus.RESERVED, now, now, null);
    }

    /**
     * Confirms the reservation.
     *
     * @throws IllegalReservationTransitionException if {@code status} cannot transition to {@link ReservationStatus#CONFIRMED}
     */
    public InventoryReservation confirm(final Instant now) {
        return this.advanceTo(ReservationStatus.CONFIRMED, now);
    }

    /**
     * Releases the reservation (compensation).
     *
     * @throws IllegalReservationTransitionException if {@code status} cannot transition to {@link ReservationStatus#RELEASED}
     */
    public InventoryReservation release(final Instant now) {
        return this.advanceTo(ReservationStatus.RELEASED, now);
    }

    private InventoryReservation advanceTo(final ReservationStatus target,
            final Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalReservationTransitionException(this.status, target);
        }
        return new InventoryReservation(this.id, this.sku, this.quantity, target, this.createdAt, now, this.version);
    }
}
