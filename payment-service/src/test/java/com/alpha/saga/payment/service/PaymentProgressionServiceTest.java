package com.alpha.saga.payment.service;

import com.alpha.saga.common.error.PermanentSagaException;
import com.alpha.saga.common.error.TemporarySagaException;
import com.alpha.saga.payment.domain.IllegalPaymentTransitionException;
import com.alpha.saga.payment.domain.PartialRefund;
import com.alpha.saga.payment.domain.Payment;
import com.alpha.saga.payment.domain.PaymentStatus;
import com.alpha.saga.payment.domain.PaymentTestClock;
import com.alpha.saga.payment.persistence.PartialRefundRepository;
import com.alpha.saga.payment.persistence.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentProgressionServiceTest {

    private static final Instant NOW = PaymentTestClock.FIXED_INSTANT;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PartialRefundRepository partialRefundRepository;

    private PaymentProgressionService service;

    @BeforeEach
    void setUp() {
        service = new PaymentProgressionService(paymentRepository, partialRefundRepository);
        lenient().when(paymentRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(partialRefundRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void requestPaymentCompletesImmediatelyForASmallAmount() {
        final UUID sagaId = UUID.randomUUID();
        when(paymentRepository.findById(sagaId)).thenReturn(Mono.empty());

        StepVerifier.create(service.requestPayment(new RequestPaymentRequest(sagaId, new BigDecimal("100.00"), NOW)))
                .assertNext(payment -> {
                    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
                    assertThat(payment.attempt()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void requestPaymentTimesOutOnFirstAttemptForAMediumAmount() {
        final UUID sagaId = UUID.randomUUID();
        when(paymentRepository.findById(sagaId)).thenReturn(Mono.empty());

        StepVerifier.create(service.requestPayment(new RequestPaymentRequest(sagaId, PaymentProgressionService.FLAKY_THRESHOLD, NOW)))
                .expectError(TemporarySagaException.class)
                .verify();

        verify(paymentRepository).save(new Payment(sagaId, PaymentProgressionService.FLAKY_THRESHOLD, PaymentStatus.PENDING, 1, NOW, NOW, null));
    }

    @Test
    void requestPaymentSucceedsOnRetryAfterATimeout() {
        final UUID sagaId = UUID.randomUUID();
        final Payment pendingFirstAttempt = Payment.request(sagaId, PaymentProgressionService.FLAKY_THRESHOLD, NOW);
        when(paymentRepository.findById(sagaId)).thenReturn(Mono.just(pendingFirstAttempt));

        StepVerifier.create(service.requestPayment(new RequestPaymentRequest(sagaId, PaymentProgressionService.FLAKY_THRESHOLD, NOW)))
                .assertNext(payment -> {
                    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
                    assertThat(payment.attempt()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    void requestPaymentIsDeclinedForALargeAmount() {
        final UUID sagaId = UUID.randomUUID();
        when(paymentRepository.findById(sagaId)).thenReturn(Mono.empty());

        StepVerifier.create(service.requestPayment(new RequestPaymentRequest(sagaId, PaymentProgressionService.HARD_DECLINE_THRESHOLD, NOW)))
                .expectError(PermanentSagaException.class)
                .verify();

        verify(paymentRepository).save(new Payment(sagaId, PaymentProgressionService.HARD_DECLINE_THRESHOLD, PaymentStatus.FAILED, 1, NOW, NOW, null));
    }

    @Test
    void requestPaymentIsIdempotentWhenAlreadyCompleted() {
        final UUID sagaId = UUID.randomUUID();
        final Payment completed = Payment.request(sagaId, new BigDecimal("100.00"), NOW).complete(NOW);
        when(paymentRepository.findById(sagaId)).thenReturn(Mono.just(completed));

        StepVerifier.create(service.requestPayment(new RequestPaymentRequest(sagaId, new BigDecimal("100.00"), NOW)))
                .assertNext(payment -> assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED))
                .verifyComplete();

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void requestPaymentIsIdempotentWhenAlreadyFailed() {
        final UUID sagaId = UUID.randomUUID();
        final Payment failed = Payment.request(sagaId, new BigDecimal("50000.00"), NOW).fail(NOW);
        when(paymentRepository.findById(sagaId)).thenReturn(Mono.just(failed));

        StepVerifier.create(service.requestPayment(new RequestPaymentRequest(sagaId, new BigDecimal("50000.00"), NOW)))
                .assertNext(payment -> assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED))
                .verifyComplete();

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void refundPaymentAdvancesFromCompletedToRefunded() {
        final UUID sagaId = UUID.randomUUID();
        final Payment completed = Payment.request(sagaId, new BigDecimal("100.00"), NOW).complete(NOW);
        when(paymentRepository.findById(sagaId)).thenReturn(Mono.just(completed));

        StepVerifier.create(service.refundPayment(new RefundPaymentRequest(sagaId, NOW)))
                .assertNext(payment -> assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED))
                .verifyComplete();
    }

    @Test
    void refundPaymentIsIdempotentWhenAlreadyRefunded() {
        final UUID sagaId = UUID.randomUUID();
        final Payment refunded = Payment.request(sagaId, new BigDecimal("100.00"), NOW).complete(NOW).refund(NOW);
        when(paymentRepository.findById(sagaId)).thenReturn(Mono.just(refunded));

        StepVerifier.create(service.refundPayment(new RefundPaymentRequest(sagaId, NOW)))
                .assertNext(payment -> assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED))
                .verifyComplete();

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void refundingAPendingPaymentThrows() {
        final UUID sagaId = UUID.randomUUID();
        final Payment pending = Payment.request(sagaId, new BigDecimal("100.00"), NOW);
        when(paymentRepository.findById(sagaId)).thenReturn(Mono.just(pending));

        StepVerifier.create(service.refundPayment(new RefundPaymentRequest(sagaId, NOW)))
                .expectError(IllegalPaymentTransitionException.class)
                .verify();
    }

    @Test
    void refundPaymentFailsWhenNotFound() {
        final UUID sagaId = UUID.randomUUID();
        when(paymentRepository.findById(sagaId)).thenReturn(Mono.empty());

        StepVerifier.create(service.refundPayment(new RefundPaymentRequest(sagaId, NOW)))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void refundPaymentThrowsPermanentlyForTheRefundFailureAmountWithoutTouchingPersistence() {
        final UUID sagaId = UUID.randomUUID();
        final Payment completed = Payment.request(sagaId, PaymentProgressionService.PERMANENT_REFUND_FAILURE_AMOUNT, NOW).complete(NOW);
        when(paymentRepository.findById(sagaId)).thenReturn(Mono.just(completed));

        StepVerifier.create(service.refundPayment(new RefundPaymentRequest(sagaId, NOW)))
                .expectError(PermanentSagaException.class)
                .verify();

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void issuePartialRefundCreatesANewStandaloneRefund() {
        final UUID sagaId = UUID.randomUUID();
        when(partialRefundRepository.findById(sagaId)).thenReturn(Mono.empty());

        StepVerifier.create(service.issuePartialRefund(new IssuePartialRefundRequest(sagaId, new BigDecimal("20.00"), NOW)))
                .assertNext(refund -> {
                    assertThat(refund.id()).isEqualTo(sagaId);
                    assertThat(refund.relatedSagaId()).isEqualTo(sagaId);
                    assertThat(refund.amount()).isEqualByComparingTo("20.00");
                    assertThat(refund.createdAt()).isEqualTo(NOW);
                })
                .verifyComplete();

        verify(paymentRepository, never()).findById(any(UUID.class));
    }

    @Test
    void issuePartialRefundIsIdempotentAndReturnsTheExistingRefund() {
        final UUID sagaId = UUID.randomUUID();
        final PartialRefund existing = new PartialRefund(sagaId, sagaId, new BigDecimal("20.00"), NOW, 0L);
        when(partialRefundRepository.findById(sagaId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.issuePartialRefund(new IssuePartialRefundRequest(sagaId, new BigDecimal("20.00"), NOW)))
                .assertNext(refund -> assertThat(refund).isEqualTo(existing))
                .verifyComplete();

        verify(partialRefundRepository, never()).save(any());
    }
}
