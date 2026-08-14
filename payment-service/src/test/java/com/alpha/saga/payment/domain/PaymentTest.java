package com.alpha.saga.payment.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.alpha.saga.payment.domain.PaymentTestClock.FIXED_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    void requestProducesAFreshPendingPaymentAtFirstAttempt() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = FIXED_INSTANT;

        final Payment payment = Payment.request(sagaId, new BigDecimal("100.00"), now);

        assertThat(payment.id()).isEqualTo(sagaId);
        assertThat(payment.amount()).isEqualByComparingTo("100.00");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.attempt()).isEqualTo(1);
        assertThat(payment.createdAt()).isEqualTo(now);
        assertThat(payment.updatedAt()).isEqualTo(now);
        assertThat(payment.version()).isNull();
    }

    @Test
    void retryIncrementsAttemptWithoutChangingStatus() {
        final Instant requestedAt = FIXED_INSTANT;
        final Instant retriedAt = Instant.parse("2026-08-03T09:05:00Z");
        final Payment payment = Payment.request(UUID.randomUUID(), new BigDecimal("100.00"), requestedAt);

        final Payment retried = payment.retry(retriedAt);

        assertThat(retried.attempt()).isEqualTo(2);
        assertThat(retried.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(retried.updatedAt()).isEqualTo(retriedAt);
        // The original instance is untouched (records are immutable).
        assertThat(payment.attempt()).isEqualTo(1);
    }

    @Test
    void completeAdvancesFromPendingToCompleted() {
        final Instant now = Instant.now();
        final Payment payment = Payment.request(UUID.randomUUID(), new BigDecimal("100.00"), now);

        final Payment completed = payment.complete(now);

        assertThat(completed.status()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void failAdvancesFromPendingToFailed() {
        final Instant now = Instant.now();
        final Payment payment = Payment.request(UUID.randomUUID(), new BigDecimal("100.00"), now);

        final Payment failed = payment.fail(now);

        assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void refundAdvancesFromCompletedToRefunded() {
        final Instant now = Instant.now();
        final Payment completed = Payment.request(UUID.randomUUID(), new BigDecimal("100.00"), now).complete(now);

        final Payment refunded = completed.refund(now);

        assertThat(refunded.status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void completingAFailedPaymentThrows() {
        final Instant now = Instant.now();
        final Payment failed = Payment.request(UUID.randomUUID(), new BigDecimal("100.00"), now).fail(now);

        assertThatThrownBy(() -> failed.complete(now))
                .isInstanceOf(IllegalPaymentTransitionException.class)
                .satisfies(exception -> {
                    final IllegalPaymentTransitionException illegal = (IllegalPaymentTransitionException) exception;
                    assertThat(illegal.from()).isEqualTo(PaymentStatus.FAILED);
                    assertThat(illegal.to()).isEqualTo(PaymentStatus.COMPLETED);
                });
    }

    @Test
    void refundingAPendingPaymentThrows() {
        final Instant now = Instant.now();
        final Payment pending = Payment.request(UUID.randomUUID(), new BigDecimal("100.00"), now);

        assertThatThrownBy(() -> pending.refund(now))
                .isInstanceOf(IllegalPaymentTransitionException.class)
                .satisfies(exception -> {
                    final IllegalPaymentTransitionException illegal = (IllegalPaymentTransitionException) exception;
                    assertThat(illegal.from()).isEqualTo(PaymentStatus.PENDING);
                    assertThat(illegal.to()).isEqualTo(PaymentStatus.REFUNDED);
                });
    }

    @Test
    void rejectsNonPositiveAmount() {
        final UUID id = UUID.randomUUID();
        final Instant now = Instant.now();

        assertThatThrownBy(() -> new Payment(id, BigDecimal.ZERO, PaymentStatus.PENDING, 1, now, now, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> new Payment(id, new BigDecimal("-1.00"), PaymentStatus.PENDING, 1, now, now, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void rejectsAttemptBelowOne() {
        final UUID id = UUID.randomUUID();
        final Instant now = Instant.now();

        assertThatThrownBy(() -> new Payment(id, new BigDecimal("100.00"), PaymentStatus.PENDING, 0, now, now, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
    }

    @Test
    void rejectsRequiredNulls() {
        final UUID id = UUID.randomUUID();
        final Instant now = Instant.now();

        assertThatThrownBy(() -> new Payment(null, new BigDecimal("100.00"), PaymentStatus.PENDING, 1, now, now, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Payment(id, null, PaymentStatus.PENDING, 1, now, now, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Payment(id, new BigDecimal("100.00"), null, 1, now, now, null))
                .isInstanceOf(NullPointerException.class);
    }
}
