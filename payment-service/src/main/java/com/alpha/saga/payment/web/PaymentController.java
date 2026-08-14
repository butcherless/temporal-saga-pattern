package com.alpha.saga.payment.web;

import com.alpha.saga.payment.service.IssuePartialRefundRequest;
import com.alpha.saga.payment.service.PaymentProgressionService;
import com.alpha.saga.payment.service.RefundPaymentRequest;
import com.alpha.saga.payment.service.RequestPaymentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** REST entry point for requesting a payment, called synchronously by the order saga Workflow's PaymentActivities. */
@RestController
@RequestMapping("/payments")
@Tag(name = "Payments", description = "Charge a saga's payment")
@Slf4j
public class PaymentController {

    private final PaymentProgressionService paymentProgressionService;

    public PaymentController(final PaymentProgressionService paymentProgressionService) {
        this.paymentProgressionService = Objects.requireNonNull(paymentProgressionService, "paymentProgressionService must not be null");
    }

    @PostMapping
    @Operation(
            summary = "Request a payment",
            description = "Charges the given amount for a saga. Idempotent by sagaId; a repeat call while still pending is a retry.")
    @ApiResponse(responseCode = "201", description = "Payment completed (or already was)")
    @ApiResponse(responseCode = "400", description = "Invalid request body (non-positive amount)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public Mono<ResponseEntity<PaymentResponseBody>> requestPayment(@RequestBody final RequestPaymentRequestBody request) {
        log.debug("requestPayment - {}", request);

        return paymentProgressionService.requestPayment(new RequestPaymentRequest(request.sagaId(), request.amount(), Instant.now()))
                .map(payment -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(new PaymentResponseBody(payment.id(), payment.status())));
    }

    @PostMapping("/{sagaId}/refund")
    @Operation(
            summary = "Refund a payment",
            description = "Compensation: called when a later saga step permanently fails, reversing a completed charge. Idempotent by sagaId.")
    @ApiResponse(responseCode = "200", description = "Payment refunded (or already was)")
    public Mono<ResponseEntity<PaymentResponseBody>> refundPayment(
            @PathVariable("sagaId") @Parameter(description = "The saga id, also the payment's id") final UUID sagaId) {
        log.debug("refundPayment - {}", sagaId);

        return paymentProgressionService.refundPayment(new RefundPaymentRequest(sagaId, Instant.now()))
                .map(payment -> ResponseEntity.ok(new PaymentResponseBody(payment.id(), payment.status())));
    }

    @PostMapping("/partial-refunds")
    @Operation(
            summary = "Issue a partial refund",
            description = "Refunds an amount for a quantity-decrease order adjustment, without a corresponding payment request. "
                    + "Idempotent by sagaId (the adjustment's own, not the original order's).")
    @ApiResponse(responseCode = "201", description = "Partial refund issued (or already was)")
    @ApiResponse(responseCode = "400", description = "Invalid request body (non-positive amount)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public Mono<ResponseEntity<PartialRefundResponseBody>> issuePartialRefund(@RequestBody final IssuePartialRefundRequestBody request) {
        log.debug("issuePartialRefund - {}", request);

        return paymentProgressionService.issuePartialRefund(new IssuePartialRefundRequest(request.sagaId(), request.amount(), Instant.now()))
                .map(refund -> ResponseEntity.status(HttpStatus.CREATED).body(new PartialRefundResponseBody(refund.id(), refund.amount())));
    }
}
