package com.company.saga.payment.web;

import com.company.saga.payment.service.PaymentProgressionService;
import com.company.saga.payment.service.RequestPaymentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;

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
}
