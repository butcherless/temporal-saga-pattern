package com.company.saga.payment.web;

import com.company.saga.payment.domain.Payment;
import com.company.saga.payment.domain.PaymentStatus;
import com.company.saga.payment.service.PaymentProgressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
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
        client = WebTestClient.bindToController(new PaymentController(paymentProgressionService))
                .controllerAdvice(new RestExceptionHandler())
                .build();
    }

    @Test
    void requestPaymentReturns201WithTheCompletedPayment() {
        final UUID sagaId = UUID.randomUUID();
        final Payment payment = Payment.request(sagaId, new java.math.BigDecimal("49.99"), Instant.now()).complete(Instant.now());
        when(paymentProgressionService.requestPayment(any())).thenReturn(Mono.just(payment));

        client.post().uri("/payments")
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
        client.post().uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","amount":-1}""".formatted(UUID.randomUUID()))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isEqualTo("amount must be positive");
    }
}
