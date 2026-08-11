package com.company.saga.order.web;

import com.company.saga.order.domain.CustomerOrder;
import com.company.saga.order.domain.IllegalOrderTransitionException;
import com.company.saga.order.domain.OrderStatus;
import com.company.saga.order.service.OrderProgressionService;
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
class OrderControllerTest {

    @Mock
    private OrderCreationHandler orderCreationHandler;

    @Mock
    private OrderProgressionService orderProgressionService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new OrderController(orderCreationHandler, orderProgressionService))
                .controllerAdvice(new RestExceptionHandler())
                .build();
    }

    @Test
    void createOrderReturns202AcceptedWithTheResponseBody() {
        final UUID sagaId = UUID.randomUUID();
        when(orderCreationHandler.createOrder(any())).thenReturn(Mono.just(new CreateOrderResponseBody(sagaId, "ORDER-2026-600001")));

        client.post().uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sku":"SKU-001","quantity":5,"amount":49.99,"businessKey":"ORDER-2026-600001"}""")
                .exchange()
                .expectStatus().isEqualTo(202)
                .expectBody()
                .jsonPath("$.sagaId").isEqualTo(sagaId.toString())
                .jsonPath("$.businessKey").isEqualTo("ORDER-2026-600001");
    }

    @Test
    void createOrderRejectsAnInvalidRequestBodyWithAProblemDetail() {
        client.post().uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sku":"SKU-001","quantity":-1,"amount":49.99}""")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isEqualTo("quantity must be positive");
    }

    @Test
    void confirmOrderReturns200WithTheConfirmedStatus() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder confirmedOrder = CustomerOrder.create(sagaId, "ORDER-2026-600002", Instant.now()).confirm(Instant.now());
        when(orderProgressionService.confirmOrder(any())).thenReturn(Mono.just(confirmedOrder));

        client.post().uri("/orders/{sagaId}/confirm", sagaId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sagaId").isEqualTo(sagaId.toString())
                .jsonPath("$.status").isEqualTo(OrderStatus.CONFIRMED.name());
    }

    @Test
    void cancelOrderReturns200WithTheCancelledStatus() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder cancelledOrder = CustomerOrder.create(sagaId, "ORDER-2026-600003", Instant.now()).cancel(Instant.now());
        when(orderProgressionService.cancelOrder(any())).thenReturn(Mono.just(cancelledOrder));

        client.post().uri("/orders/{sagaId}/cancel", sagaId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sagaId").isEqualTo(sagaId.toString())
                .jsonPath("$.status").isEqualTo(OrderStatus.CANCELLED.name());
    }

    /**
     * {@link IllegalOrderTransitionException} extends plain {@code IllegalStateException}, not
     * {@code SagaException} (an internal invariant violation, not a classified business error —
     * see the exception's own javadoc), and {@code RestExceptionHandler} deliberately doesn't map
     * it: it's meant to surface as an unhandled 500, not a shaped {@code ProblemDetail}.
     */
    @Test
    void confirmOrderReturns500WhenTheOrderCannotLegallyBeConfirmed() {
        final UUID sagaId = UUID.randomUUID();
        when(orderProgressionService.confirmOrder(any()))
                .thenReturn(Mono.error(new IllegalOrderTransitionException(OrderStatus.CANCELLED, OrderStatus.CONFIRMED)));

        client.post().uri("/orders/{sagaId}/confirm", sagaId)
                .exchange()
                .expectStatus().is5xxServerError();
    }

    /**
     * Same reasoning as {@link #confirmOrderReturns500WhenTheOrderCannotLegallyBeConfirmed} —
     * cancelling an already-{@code CONFIRMED} order (proposal §9.3's {@code PIVOT} rule) is an
     * internal invariant violation, not a classified business error.
     */
    @Test
    void cancelOrderReturns500WhenTheOrderCannotLegallyBeCancelled() {
        final UUID sagaId = UUID.randomUUID();
        when(orderProgressionService.cancelOrder(any()))
                .thenReturn(Mono.error(new IllegalOrderTransitionException(OrderStatus.CONFIRMED, OrderStatus.CANCELLED)));

        client.post().uri("/orders/{sagaId}/cancel", sagaId)
                .exchange()
                .expectStatus().is5xxServerError();
    }
}
