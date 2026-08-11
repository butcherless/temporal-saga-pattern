package com.company.saga.order.service;

import com.company.saga.common.error.PermanentSagaException;
import com.company.saga.order.domain.CustomerOrder;
import com.company.saga.order.domain.IllegalOrderTransitionException;
import com.company.saga.order.domain.OrderStatus;
import com.company.saga.order.domain.OrderTestClock;
import com.company.saga.order.persistence.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderProgressionServiceTest {

    private static final Instant NOW = OrderTestClock.FIXED_INSTANT;
    private static final String BUSINESS_KEY = "ORDER-2026-000001";
    private static final String CONFIRMATION_FAILURE_BUSINESS_KEY =
            "ORDER-2026-%s".formatted(OrderProgressionService.PERMANENT_CONFIRMATION_FAILURE_MARKER);

    @Mock
    private OrderRepository orderRepository;

    private OrderProgressionService service;

    @BeforeEach
    void setUp() {
        service = new OrderProgressionService(orderRepository);
        lenient().when(orderRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void createOrderCreatesANewOrderWhenBusinessKeyIsUnseen() {
        final UUID sagaId = UUID.randomUUID();
        when(orderRepository.findByBusinessKey(BUSINESS_KEY)).thenReturn(Mono.empty());

        StepVerifier.create(service.createOrder(new CreateOrderRequest(sagaId, BUSINESS_KEY, NOW)))
                .assertNext(order -> {
                    assertThat(order.id()).isEqualTo(sagaId);
                    assertThat(order.businessKey()).isEqualTo(BUSINESS_KEY);
                    assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
                })
                .verifyComplete();
    }

    @Test
    void createOrderIsIdempotentAndReturnsTheExistingOrderForAKnownBusinessKey() {
        final CustomerOrder existing = CustomerOrder.create(UUID.randomUUID(), BUSINESS_KEY, NOW);
        when(orderRepository.findByBusinessKey(BUSINESS_KEY)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.createOrder(new CreateOrderRequest(UUID.randomUUID(), BUSINESS_KEY, NOW)))
                .assertNext(order -> assertThat(order).isEqualTo(existing))
                .verifyComplete();

        verify(orderRepository, never()).save(any());
    }

    @Test
    void confirmOrderAdvancesFromPendingToConfirmed() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder pending = CustomerOrder.create(sagaId, BUSINESS_KEY, NOW);
        when(orderRepository.findById(sagaId)).thenReturn(Mono.just(pending));

        StepVerifier.create(service.confirmOrder(new ConfirmOrderRequest(sagaId, NOW)))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED))
                .verifyComplete();
    }

    @Test
    void confirmOrderIsIdempotentWhenAlreadyConfirmed() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder confirmed = CustomerOrder.create(sagaId, BUSINESS_KEY, NOW).confirm(NOW);
        when(orderRepository.findById(sagaId)).thenReturn(Mono.just(confirmed));

        StepVerifier.create(service.confirmOrder(new ConfirmOrderRequest(sagaId, NOW)))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED))
                .verifyComplete();

        verify(orderRepository, never()).save(any());
    }

    @Test
    void confirmOrderFailsWhenTheOrderDoesNotExist() {
        final UUID sagaId = UUID.randomUUID();
        when(orderRepository.findById(sagaId)).thenReturn(Mono.empty());

        StepVerifier.create(service.confirmOrder(new ConfirmOrderRequest(sagaId, NOW)))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void confirmingACancelledOrderThrows() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder cancelled = CustomerOrder.create(sagaId, BUSINESS_KEY, NOW).cancel(NOW);
        when(orderRepository.findById(sagaId)).thenReturn(Mono.just(cancelled));

        StepVerifier.create(service.confirmOrder(new ConfirmOrderRequest(sagaId, NOW)))
                .expectError(IllegalOrderTransitionException.class)
                .verify();
    }

    @Test
    void confirmOrderThrowsPermanentlyForTheConfirmationFailureMarkerWithoutTouchingPersistence() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder pending = CustomerOrder.create(sagaId, CONFIRMATION_FAILURE_BUSINESS_KEY, NOW);
        when(orderRepository.findById(sagaId)).thenReturn(Mono.just(pending));

        StepVerifier.create(service.confirmOrder(new ConfirmOrderRequest(sagaId, NOW)))
                .expectError(PermanentSagaException.class)
                .verify();

        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancelOrderAdvancesFromPendingToCancelled() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder pending = CustomerOrder.create(sagaId, BUSINESS_KEY, NOW);
        when(orderRepository.findById(sagaId)).thenReturn(Mono.just(pending));

        StepVerifier.create(service.cancelOrder(new CancelOrderRequest(sagaId, NOW)))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED))
                .verifyComplete();
    }

    @Test
    void cancelOrderIsIdempotentWhenAlreadyCancelled() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder cancelled = CustomerOrder.create(sagaId, BUSINESS_KEY, NOW).cancel(NOW);
        when(orderRepository.findById(sagaId)).thenReturn(Mono.just(cancelled));

        StepVerifier.create(service.cancelOrder(new CancelOrderRequest(sagaId, NOW)))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED))
                .verifyComplete();

        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancellingAConfirmedOrderThrows() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder confirmed = CustomerOrder.create(sagaId, BUSINESS_KEY, NOW).confirm(NOW);
        when(orderRepository.findById(sagaId)).thenReturn(Mono.just(confirmed));

        StepVerifier.create(service.cancelOrder(new CancelOrderRequest(sagaId, NOW)))
                .expectError(IllegalOrderTransitionException.class)
                .verify();
    }

    @Test
    void cancelOrderFailsWhenTheOrderDoesNotExist() {
        final UUID sagaId = UUID.randomUUID();
        when(orderRepository.findById(sagaId)).thenReturn(Mono.empty());

        StepVerifier.create(service.cancelOrder(new CancelOrderRequest(sagaId, NOW)))
                .expectError(IllegalStateException.class)
                .verify();
    }
}
