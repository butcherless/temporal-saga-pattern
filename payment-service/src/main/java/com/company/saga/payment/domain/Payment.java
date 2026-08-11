package com.company.saga.payment.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Payment Service's own aggregate (proposal, section 6.3): the payment behind one Saga. Its
 * {@code id} is the Saga's own id (Step 5 plan, decision 6) — this PoC has exactly one payment
 * per saga. {@code attempt} backs the deterministic fake-gateway rule in
 * {@code PaymentProgressionService} (decision 4): it starts at 1 and is incremented each time
 * {@code requestPayment} is retried while still {@link PaymentStatus#PENDING}.
 */
@Table("payment")
public record Payment(
        @Id UUID id,
        BigDecimal amount,
        PaymentStatus status,
        int attempt,
        Instant createdAt,
        Instant updatedAt,
        @Version Long version) {

    public Payment {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
    }

    /** Creates a brand-new payment request, in {@link PaymentStatus#PENDING} at its first attempt. */
    public static Payment request(final UUID sagaId,
            final BigDecimal amount,
            final Instant now) {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        return new Payment(sagaId, amount, PaymentStatus.PENDING, 1, now, now, null);
    }

    /** Records another attempt at the same request, without changing {@code status}. */
    public Payment retry(final Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return new Payment(id, amount, status, attempt + 1, createdAt, now, version);
    }

    /**
     * Marks the payment as completed.
     *
     * @throws IllegalPaymentTransitionException if {@code status} cannot transition to {@link PaymentStatus#COMPLETED}
     */
    public Payment complete(final Instant now) {
        return advanceTo(PaymentStatus.COMPLETED, now);
    }

    /**
     * Marks the payment as permanently failed.
     *
     * @throws IllegalPaymentTransitionException if {@code status} cannot transition to {@link PaymentStatus#FAILED}
     */
    public Payment fail(final Instant now) {
        return advanceTo(PaymentStatus.FAILED, now);
    }

    /**
     * Refunds the payment (compensation).
     *
     * @throws IllegalPaymentTransitionException if {@code status} cannot transition to {@link PaymentStatus#REFUNDED}
     */
    public Payment refund(final Instant now) {
        return advanceTo(PaymentStatus.REFUNDED, now);
    }

    private Payment advanceTo(final PaymentStatus target,
            final Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalPaymentTransitionException(status, target);
        }
        return new Payment(id, amount, target, attempt, createdAt, now, version);
    }
}
