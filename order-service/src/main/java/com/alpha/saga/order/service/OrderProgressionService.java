package com.alpha.saga.order.service;

import com.alpha.saga.common.error.PermanentSagaException;
import com.alpha.saga.order.domain.CustomerOrder;
import com.alpha.saga.order.domain.IllegalOrderTransitionException;
import com.alpha.saga.order.domain.OrderNotFoundException;
import com.alpha.saga.order.domain.OrderStatus;
import com.alpha.saga.order.persistence.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/**
 * Order Service's idempotent use cases (proposal, section 10.5): create, confirm, and cancel an
 * order. {@code createOrder} is idempotent by {@code businessKey}; {@code confirmOrder} and
 * {@code cancelOrder} are idempotent by {@code sagaId} — replaying a command that already
 * happened returns the current state instead of re-applying it or failing. There is no real
 * order-confirmation gateway in this PoC: a fixed businessKey marker recognized by
 * {@link #simulateConfirmFault} stands in for proposal §17.3 scenario 7 (mirroring
 * {@code InventoryProgressionService}'s/{@code PaymentProgressionService}'s own deterministic
 * fake gateways) — {@code businessKey} is the only free-form input {@link CustomerOrder} carries
 * besides its {@code sagaId}/{@code status}, since this service has no {@code sku}/{@code amount} field.
 */
@Slf4j
public class OrderProgressionService {

    /**
     * PoC-only, not a real business rule: confirming an order whose businessKey contains this marker always fails (proposal §17.3 scenario 7).
     * Public (not private) so tests across packages can reference it directly instead of retyping the literal.
     */
    public static final String PERMANENT_CONFIRMATION_FAILURE_MARKER = "CONFIRMFAIL-INPUTDATA-7";

    private final OrderRepository orderRepository;

    public OrderProgressionService(final OrderRepository orderRepository) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
    }

    /**
     * Creates the order for {@code request.businessKey()}, or returns the existing one if already created.
     */
    public Mono<CustomerOrder> createOrder(final CreateOrderRequest request) {
        log.debug("createOrder - {}", request);

        return this.orderRepository.findByBusinessKey(request.businessKey())
                .switchIfEmpty(Mono.defer(() -> this.orderRepository.save(
                        CustomerOrder.create(request.sagaId(), request.businessKey(), request.now()))));
    }

    /**
     * Confirms the order, or returns it unchanged if already {@link OrderStatus#CONFIRMED}.
     *
     * @throws IllegalOrderTransitionException if the order cannot legally be confirmed (e.g. already {@code CANCELLED})
     * @throws PermanentSagaException          every time {@link #PERMANENT_CONFIRMATION_FAILURE_MARKER}'s order is confirmed (PoC fault injection)
     */
    public Mono<CustomerOrder> confirmOrder(final ConfirmOrderRequest request) {
        log.debug("confirmOrder - {}", request);
        return this.loadOrder(request.sagaId())
                .flatMap(order -> OrderStatus.CONFIRMED.equals(order.status())
                        ? Mono.just(order)
                        : this.simulateConfirmFault(order)
                        .then(Mono.defer(() -> this.orderRepository.save(order.confirm(request.now())))));
    }

    /**
     * Cancels the order (compensation), or returns it unchanged if already {@link OrderStatus#CANCELLED}.
     *
     * @throws IllegalOrderTransitionException if the order cannot legally be cancelled (e.g. already {@code CONFIRMED} — proposal §9.3's {@code PIVOT} rule)
     */
    public Mono<CustomerOrder> cancelOrder(final CancelOrderRequest request) {
        log.debug("cancelOrder - {}", request);
        return this.loadOrder(request.sagaId())
                .flatMap(order -> order.status() == OrderStatus.CANCELLED
                        ? Mono.just(order)
                        : this.orderRepository.save(order.cancel(request.now())));
    }

    /**
     * Reads the order for {@code sagaId}, for {@code GET /orders/{sagaId}}. Unlike {@link #loadOrder},
     * which backs {@code confirmOrder}/{@code cancelOrder}'s "this sagaId must already have an
     * order" internal invariant, a missing order here is a legitimate outcome of a caller-supplied
     * id — {@link OrderNotFoundException} maps to a 404, not the invariant violation's 500.
     */
    public Mono<CustomerOrder> getOrder(final UUID sagaId) {
        log.debug("getOrder - {}", sagaId);
        return this.orderRepository.findById(sagaId)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(sagaId)));
    }

    /**
     * PoC fault injection for §17.3 scenario 7: always throws before persisting a confirmation for {@link #PERMANENT_CONFIRMATION_FAILURE_MARKER}.
     */
    private Mono<Void> simulateConfirmFault(final CustomerOrder order) {
        return order.businessKey().contains(PERMANENT_CONFIRMATION_FAILURE_MARKER)
                ? Mono.error(new PermanentSagaException(
                "Simulated unrecoverable order confirmation failure for businessKey %s".formatted(order.businessKey())))
                : Mono.empty();
    }

    private Mono<CustomerOrder> loadOrder(final UUID sagaId) {
        return this.orderRepository.findById(sagaId)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("Order not found: %s".formatted(sagaId))));
    }
}
