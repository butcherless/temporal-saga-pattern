package com.company.saga.order.web;

import com.company.saga.common.workflow.OrderSagaInput;
import com.company.saga.common.workflow.OrderSagaWorkflow;
import com.company.saga.common.workflow.SagaTaskQueues;
import com.company.saga.order.domain.CustomerOrder;
import com.company.saga.order.service.CreateOrderRequest;
import com.company.saga.order.service.OrderProgressionService;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Backs {@code POST /orders}: creates the order, then starts the {@link OrderSagaWorkflow}
 * (Workflow Id = {@code businessKey}, so restarting the saga for an already-started businessKey
 * is a client-side no-op rather than a duplicate execution). Unlike the custom implementation,
 * there is no Outbox write here — starting a Workflow Execution is itself the durable handoff to
 * the saga; Temporal's own server persists it.
 */
@Slf4j
public class OrderCreationHandler {

    private final OrderProgressionService orderProgressionService;
    private final WorkflowClient workflowClient;

    public OrderCreationHandler(final OrderProgressionService orderProgressionService, final WorkflowClient workflowClient) {
        this.orderProgressionService = Objects.requireNonNull(orderProgressionService, "orderProgressionService must not be null");
        this.workflowClient = Objects.requireNonNull(workflowClient, "workflowClient must not be null");
    }

    public Mono<CreateOrderResponseBody> createOrder(final CreateOrderRequestBody request) {
        log.debug("createOrder - {}", request);
        final UUID sagaId = UUID.randomUUID();
        final String businessKey = request.hasNoBusinessKey() ? sagaId.toString() : request.businessKey();
        final Instant now = Instant.now();

        return orderProgressionService.createOrder(new CreateOrderRequest(sagaId, businessKey, now))
                .flatMap(order -> startSagaIfNewlyCreated(order, sagaId, request)
                        .thenReturn(new CreateOrderResponseBody(order.id(), order.businessKey())));
    }

    /**
     * Starts the saga only for the call that actually created the order: {@code order.id()}
     * differs from the freshly-minted {@code sagaId} whenever {@code orderProgressionService}
     * returned an already-existing row instead (idempotent by businessKey, proposal §17.3's
     * duplicate-submission scenario). {@link WorkflowExecutionAlreadyStarted} is swallowed as a
     * defense against the narrow race where two concurrent duplicate requests both observe no
     * existing order — either way, the caller sees the same successful, idempotent outcome.
     */
    private Mono<Void> startSagaIfNewlyCreated(final CustomerOrder order, final UUID sagaId, final CreateOrderRequestBody request) {
        if (!order.id().equals(sagaId)) {
            return Mono.empty();
        }

        final OrderSagaWorkflow workflow = workflowClient.newWorkflowStub(
                OrderSagaWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(order.businessKey())
                        .setTaskQueue(SagaTaskQueues.ORDER_SAGA)
                        .build());
        final OrderSagaInput input = new OrderSagaInput(order.id(), order.businessKey(), request.sku(), request.quantity(), request.amount());

        // WorkflowClient.start is a blocking gRPC call; run it off the WebFlux event loop.
        return Mono.fromRunnable(() -> WorkflowClient.start(workflow::process, input))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(WorkflowExecutionAlreadyStarted.class, error -> Mono.empty())
                .then();
    }
}
