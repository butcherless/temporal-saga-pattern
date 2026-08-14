package com.alpha.saga.order.web;

import com.alpha.saga.common.error.TemporarySagaException;
import com.alpha.saga.common.workflow.OrderSagaProgress;
import com.alpha.saga.common.workflow.OrderSagaWorkflow;
import com.alpha.saga.order.domain.CustomerOrder;
import com.alpha.saga.order.service.OrderProgressionService;
import io.temporal.client.WorkflowClient;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Objects;
import java.util.UUID;

/**
 * Backs {@code GET /orders/{sagaId}}: reads the order's durable status, and — only while it's
 * still {@link com.alpha.saga.order.domain.OrderStatus#PENDING} — also reads the saga's live
 * step-level progress via {@link OrderSagaWorkflow#getProgress()}. Once the order is terminal
 * ({@code CONFIRMED}/{@code CANCELLED}), the outcome is derived from the durable row alone,
 * without depending on the Workflow Execution still being open/queryable in Temporal's retention
 * window.
 */
@Slf4j
public class OrderQueryHandler {

    private final OrderProgressionService orderProgressionService;
    private final WorkflowClient workflowClient;
    private final Scheduler blockingCallScheduler;

    public OrderQueryHandler(
            final OrderProgressionService orderProgressionService,
            final WorkflowClient workflowClient,
            final Scheduler blockingCallScheduler) {
        this.orderProgressionService = Objects.requireNonNull(orderProgressionService, "orderProgressionService must not be null");
        this.workflowClient = Objects.requireNonNull(workflowClient, "workflowClient must not be null");
        this.blockingCallScheduler = Objects.requireNonNull(blockingCallScheduler, "blockingCallScheduler must not be null");
    }

    public Mono<OrderStatusResponseBody> getOrderStatus(final UUID sagaId) {
        log.debug("getOrderStatus - {}", sagaId);
        return orderProgressionService.getOrder(sagaId)
                .flatMap(order -> switch (order.status()) {
                    case CONFIRMED -> Mono.just(new OrderStatusResponseBody(order.id(), order.status(), OrderSagaProgress.COMPLETED));
                    case CANCELLED -> Mono.just(new OrderStatusResponseBody(order.id(), order.status(), OrderSagaProgress.COMPENSATED));
                    case PENDING -> queryWorkflowProgress(order);
                });
    }

    private Mono<OrderStatusResponseBody> queryWorkflowProgress(final CustomerOrder order) {
        final OrderSagaWorkflow workflow = workflowClient.newWorkflowStub(OrderSagaWorkflow.class, order.businessKey());
        // WorkflowStub query methods are a blocking gRPC call; run it off the WebFlux event loop,
        // same as OrderCreationHandler's own blocking WorkflowClient.start bridge.
        return Mono.fromCallable(workflow::getProgress)
                .subscribeOn(blockingCallScheduler)
                .map(progress -> new OrderStatusResponseBody(order.id(), order.status(), progress))
                // The order is still PENDING, so the Workflow Execution should exist and be
                // queryable; a failure here (not found, gRPC timeout, ...) is a transient
                // condition from the caller's point of view, not a permanent one — mapped to 503
                // by RestExceptionHandler's existing TemporarySagaException handling, same as
                // every other gateway-style failure in this repo.
                .onErrorMap(error -> new TemporarySagaException(
                        "Saga progress temporarily unavailable for sagaId %s".formatted(order.id()), error));
    }
}
