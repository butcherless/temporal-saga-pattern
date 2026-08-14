package com.alpha.saga.payment.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A standalone refund for a quantity-decrease order adjustment (docs/order-adjustment-and-status-
 * query-plan.md, Part A), deliberately independent of {@link Payment}: a decrease-only adjustment
 * never had a payment request of its own to refund, and {@code Payment.refund()} always refunds
 * the entire original {@code amount} rather than an arbitrary one. {@code id} is the adjustment's
 * own saga id (same "id is the saga's id" convention as {@link Payment}); {@code relatedSagaId} is
 * carried for traceability back to the amount/order context. No status machine — unlike
 * {@link Payment}, this is a single fire-and-forget event with no later reversal.
 *
 * <p>{@code version} exists purely so Spring Data R2DBC's {@code save()} recognizes a freshly
 * created instance (always constructed with {@code version = null}) as new and issues an
 * {@code INSERT}: without it, a manually-assigned, always-non-null {@code @Id} makes {@code save()}
 * assume the row already exists and silently issues a no-op {@code UPDATE} instead — the same
 * reason {@link Payment}/{@code InventoryReservation}/{@code CustomerOrder} all carry one too.
 */
@Table("partial_refund")
public record PartialRefund(
        @Id UUID id,
        UUID relatedSagaId,
        BigDecimal amount,
        Instant createdAt,
        @Version Long version) {

    public PartialRefund {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(relatedSagaId, "relatedSagaId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
