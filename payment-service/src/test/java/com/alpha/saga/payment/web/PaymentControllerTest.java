package com.alpha.saga.payment.web;

import com.alpha.saga.common.error.PermanentSagaException;
import com.alpha.saga.common.error.TemporarySagaException;
import com.alpha.saga.payment.domain.PartialRefund;
import com.alpha.saga.payment.domain.Payment;
import com.alpha.saga.payment.domain.PaymentStatus;
import com.alpha.saga.payment.service.PaymentProgressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private PaymentProgressionService paymentProgressionService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        this.client = WebTestClient.bindToController(new PaymentController(this.paymentProgressionService))
                .controllerAdvice(new RestExceptionHandler())
                .build();
    }

    @Test
    void requestPaymentReturns201WithTheCompletedPayment() {
        final UUID sagaId = UUID.randomUUID();
        final Payment payment = Payment.request(sagaId, new java.math.BigDecimal("49.99"), Instant.now()).complete(Instant.now());
        when(this.paymentProgressionService.requestPayment(any())).thenReturn(Mono.just(payment));

        this.client.post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","amount":49.99}""".formatted(sagaId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.sagaId").isEqualTo(sagaId.toString())
                .jsonPath("$.status").isEqualTo(PaymentStatus.COMPLETED.name());
    }

    @Test
    void requestPaymentRejectsAnInvalidRequestBodyWithAProblemDetail() {
        final ResponseSpec response = this.client.post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","amount":-1}""".formatted(UUID.randomUUID()))
                .exchange();

        assertProblemDetail(response, 400, "amount must be positive");
    }

    @Test
    void requestPaymentReturns422WhenTheGatewayPermanentlyDeclinesThePayment() {
        final UUID sagaId = UUID.randomUUID();
        when(this.paymentProgressionService.requestPayment(any()))
                .thenReturn(Mono.error(new PermanentSagaException("Payment declined for amount 15000.00")));

        final ResponseSpec response = this.client.post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","amount":15000.00}""".formatted(sagaId))
                .exchange();

        assertProblemDetail(response, 422, "Payment declined for amount 15000.00");
    }

    @Test
    void requestPaymentReturns503WhenTheGatewayTimesOut() {
        final UUID sagaId = UUID.randomUUID();
        when(this.paymentProgressionService.requestPayment(any()))
                .thenReturn(Mono.error(new TemporarySagaException("Payment gateway timeout for amount 2000.00")));

        final ResponseSpec response = this.client.post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","amount":2000.00}""".formatted(sagaId))
                .exchange();

        assertProblemDetail(response, 503, "Payment gateway timeout for amount 2000.00");
    }

    @Test
    void refundPaymentReturns200WithTheRefundedStatus() {
        final UUID sagaId = UUID.randomUUID();
        final Payment refunded = Payment.request(sagaId, new java.math.BigDecimal("49.99"), Instant.now())
                .complete(Instant.now())
                .refund(Instant.now());
        when(this.paymentProgressionService.refundPayment(any())).thenReturn(Mono.just(refunded));

        this.client.post().uri("/payments/{sagaId}/refund", sagaId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sagaId").isEqualTo(sagaId.toString())
                .jsonPath("$.status").isEqualTo(PaymentStatus.REFUNDED.name());
    }

    @Test
    void refundPaymentReturns422WhenTheRefundIsPermanentlyUnrecoverable() {
        final UUID sagaId = UUID.randomUUID();
        when(this.paymentProgressionService.refundPayment(any()))
                .thenReturn(Mono.error(new PermanentSagaException("Simulated unrecoverable refund failure for amount 750.00")));

        final ResponseSpec response = this.client.post().uri("/payments/{sagaId}/refund", sagaId).exchange();

        assertProblemDetail(response, 422, "Simulated unrecoverable refund failure for amount 750.00");
    }

    @Test
    void issuePartialRefundReturns201WithTheRefund() {
        final UUID sagaId = UUID.randomUUID();
        final PartialRefund refund = new PartialRefund(sagaId, sagaId, new java.math.BigDecimal("20.00"), Instant.now(), null);
        when(this.paymentProgressionService.issuePartialRefund(any())).thenReturn(Mono.just(refund));

        this.client.post().uri("/payments/partial-refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","amount":20.00}""".formatted(sagaId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.sagaId").isEqualTo(sagaId.toString())
                .jsonPath("$.amount").isEqualTo(20.00);
    }

    @Test
    void issuePartialRefundRejectsAnInvalidRequestBodyWithAProblemDetail() {
        final ResponseSpec response = this.client.post().uri("/payments/partial-refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","amount":-1}""".formatted(UUID.randomUUID()))
                .exchange();

        assertProblemDetail(response, 400, "amount must be positive");
    }

    /**
     * Shared shape behind every {@code ProblemDetail} assertion above (400 for bad input, 422/503
     * for the two {@code SagaException} classifications): status code, {@code application/problem+json}
     * content type, and the {@code $.status}/{@code $.detail} body fields.
     */
    private static void assertProblemDetail(final ResponseSpec response,
            final int expectedStatus,
            final String expectedDetail) {
        response.expectStatus().isEqualTo(expectedStatus)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(expectedStatus)
                .jsonPath("$.detail").isEqualTo(expectedDetail);
    }
}
