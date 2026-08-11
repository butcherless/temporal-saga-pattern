package com.company.saga.payment.service;

import com.company.saga.common.error.PermanentSagaException;
import com.company.saga.common.error.TemporarySagaException;
import com.company.saga.payment.domain.IllegalPaymentTransitionException;
import com.company.saga.payment.domain.PartialRefund;
import com.company.saga.payment.domain.Payment;
import com.company.saga.payment.domain.PaymentStatus;
import com.company.saga.payment.persistence.PartialRefundRepository;
import com.company.saga.payment.persistence.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Payment Service's idempotent use cases (proposal, section 10.5): request and refund a payment.
 * There is no real payment gateway in this PoC (Step 5 plan, decision 4): {@code requestPayment}
 * runs a deterministic rule based on {@code amount} and the persisted {@code attempt} count
 * instead, so the temporary/permanent error scenarios (proposal §17.3) can be exercised with
 * fixed amounts rather than a fake external system. {@code refundPayment} has its own fixed-amount
 * fault trigger ({@link #PERMANENT_REFUND_FAILURE_AMOUNT}) for proposal §17.3 scenario 8.
 */
@Slf4j
public class PaymentProgressionService {

    /** PoC-only, not a real business rule: amounts at or above this are always declined. Widened for tests to reference directly. */
    public static final BigDecimal HARD_DECLINE_THRESHOLD = new BigDecimal("10000.00");

    /** PoC-only, not a real business rule: amounts at or above this time out on the first attempt. Widened for tests to reference directly. */
    public static final BigDecimal FLAKY_THRESHOLD = new BigDecimal("1000.00");

    /** PoC-only, not a real business rule: refunding a payment for exactly this amount always fails (proposal §17.3 scenario 8). Widened for tests to reference directly. */
    public static final BigDecimal PERMANENT_REFUND_FAILURE_AMOUNT = new BigDecimal("750.00");

    private final PaymentRepository paymentRepository;
    private final PartialRefundRepository partialRefundRepository;

    public PaymentProgressionService(final PaymentRepository paymentRepository,
            final PartialRefundRepository partialRefundRepository) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository must not be null");
        this.partialRefundRepository = Objects.requireNonNull(partialRefundRepository, "partialRefundRepository must not be null");
    }

    /**
     * Requests the payment for {@code request.sagaId()}, or resumes/returns it if a request
     * already exists: a repeat call while still {@link PaymentStatus#PENDING} is a retry
     * (increments {@code attempt} and re-evaluates); a repeat call once settled ({@code COMPLETED}/
     * {@code FAILED}/{@code REFUNDED}) returns the current state.
     *
     * @throws TemporarySagaException if the simulated gateway times out on this attempt
     * @throws PermanentSagaException if the simulated gateway declines the payment outright
     */
    public Mono<Payment> requestPayment(final RequestPaymentRequest request) {
        log.debug("requestPayment - {}", request);
        return paymentRepository.findById(request.sagaId())
                .flatMap(existing -> existing.status() == PaymentStatus.PENDING
                        ? evaluate(existing.retry(request.now()), request.now())
                        : Mono.just(existing))
                .switchIfEmpty(Mono.defer(() -> evaluate(
                        Payment.request(request.sagaId(), request.amount(), request.now()), request.now())));
    }

    /**
     * Refunds the payment (compensation), or returns it unchanged if already {@link PaymentStatus#REFUNDED}.
     *
     * @throws IllegalPaymentTransitionException if the payment cannot legally be refunded (e.g. never {@code COMPLETED})
     * @throws PermanentSagaException every time {@link #PERMANENT_REFUND_FAILURE_AMOUNT}'s payment is refunded (PoC fault injection)
     */
    public Mono<Payment> refundPayment(final RefundPaymentRequest request) {
        log.debug("refundPayment - {}", request);
        return loadPayment(request.sagaId())
                .flatMap(payment -> payment.status() == PaymentStatus.REFUNDED
                        ? Mono.just(payment)
                        : simulateRefundFault(payment)
                                .then(Mono.defer(() -> paymentRepository.save(payment.refund(request.now())))));
    }

    /**
     * Issues a standalone refund for {@code request.sagaId()} (an order adjustment's own saga id,
     * not necessarily tied to any {@link Payment} row), or returns the existing one if already
     * issued. Backs a quantity-decrease order adjustment
     * (docs/order-adjustment-and-status-query-plan.md, Part A): unlike {@link #refundPayment},
     * this doesn't require — or touch — a pre-existing {@code Payment}.
     */
    public Mono<PartialRefund> issuePartialRefund(final IssuePartialRefundRequest request) {
        log.debug("issuePartialRefund - {}", request);
        return partialRefundRepository.findById(request.sagaId())
                .switchIfEmpty(Mono.defer(() -> partialRefundRepository.save(
                        new PartialRefund(request.sagaId(), request.sagaId(), request.amount(), request.now(), null))));
    }

    private Mono<Payment> evaluate(final Payment candidate,
            final Instant now) {
        final BigDecimal amount = candidate.amount();
        if (amount.compareTo(HARD_DECLINE_THRESHOLD) >= 0) {
            return paymentRepository.save(candidate.fail(now))
                    .then(Mono.error(new PermanentSagaException("Payment declined for amount %s".formatted(amount))));
        }
        if (amount.compareTo(FLAKY_THRESHOLD) >= 0 && candidate.attempt() == 1) {
            return paymentRepository.save(candidate)
                    .then(Mono.error(new TemporarySagaException("Payment gateway timeout for amount %s".formatted(amount))));
        }
        return paymentRepository.save(candidate.complete(now));
    }

    /** PoC fault injection for §17.3 scenario 8: always throws before persisting a refund for {@link #PERMANENT_REFUND_FAILURE_AMOUNT}. */
    private Mono<Void> simulateRefundFault(final Payment payment) {
        return payment.amount().compareTo(PERMANENT_REFUND_FAILURE_AMOUNT) == 0
                ? Mono.error(new PermanentSagaException("Simulated unrecoverable refund failure for amount %s".formatted(payment.amount())))
                : Mono.empty();
    }

    private Mono<Payment> loadPayment(final UUID sagaId) {
        return paymentRepository.findById(sagaId)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("Payment not found: %s".formatted(sagaId))));
    }
}
