package com.alpha.saga.order.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Order Service's own aggregate (proposal, section 6.1): the order behind one Saga. Its
 * {@code id} is the Saga's own id (Step 5 plan, decision 6) — this PoC has exactly one order
 * per saga, so a separate surrogate id would be an unused column. {@code businessKey} is the
 * idempotency key (proposal §10.5), independent of {@code id}. Named {@code CustomerOrder}, not
 * {@code Order}: {@code Order} collides with {@code org.springframework.data.domain.Sort.Order},
 * and {@code order} is a reserved SQL keyword.
 */
@Table("customer_order")
public record CustomerOrder(
        @Id UUID id,
        String businessKey,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt,
        @Version Long version) {

    public CustomerOrder {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(businessKey, "businessKey must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (businessKey.isBlank()) {
            throw new IllegalArgumentException("businessKey must not be blank");
        }
    }

    /** Creates a brand-new order, in {@link OrderStatus#PENDING}. */
    public static CustomerOrder create(final UUID sagaId,
            final String businessKey,
            final Instant now) {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        return new CustomerOrder(sagaId, businessKey, OrderStatus.PENDING, now, now, null);
    }

    /**
     * Confirms the order.
     *
     * @throws IllegalOrderTransitionException if {@code status} cannot transition to {@link OrderStatus#CONFIRMED}
     */
    public CustomerOrder confirm(final Instant now) {
        return this.advanceTo(OrderStatus.CONFIRMED, now);
    }

    /**
     * Cancels the order (compensation).
     *
     * @throws IllegalOrderTransitionException if {@code status} cannot transition to {@link OrderStatus#CANCELLED}
     */
    public CustomerOrder cancel(final Instant now) {
        return this.advanceTo(OrderStatus.CANCELLED, now);
    }

    private CustomerOrder advanceTo(final OrderStatus target,
            final Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalOrderTransitionException(this.status, target);
        }
        return new CustomerOrder(this.id, this.businessKey, target, this.createdAt, now, this.version);
    }
}
