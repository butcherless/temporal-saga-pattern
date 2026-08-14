package com.alpha.saga.order.web;

import com.alpha.saga.common.error.TemporarySagaException;
import com.alpha.saga.common.workflow.OrderSagaInput;
import com.alpha.saga.common.workflow.OrderSagaProgress;
import com.alpha.saga.common.workflow.OrderSagaWorkflow;
import com.alpha.saga.common.workflow.SagaTaskQueues;
import com.alpha.saga.order.domain.CustomerOrder;
import com.alpha.saga.order.domain.OrderNotFoundException;
import com.alpha.saga.order.domain.OrderStatus;
import com.alpha.saga.order.service.OrderProgressionService;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderQueryHandlerTest {

    @Mock
    private OrderProgressionService orderProgressionService;

    private TestWorkflowEnvironment testEnv;

    @AfterEach
    void tearDown() {
        if (this.testEnv != null) {
            this.testEnv.close();
        }
    }

    @Test
    void getOrderStatusReturnsCompletedWithoutQueryingTheWorkflowWhenOrderIsConfirmed() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder confirmed = CustomerOrder.create(sagaId, "ORDER-2026-900001", Instant.now()).confirm(Instant.now());
        when(this.orderProgressionService.getOrder(sagaId)).thenReturn(Mono.just(confirmed));

        final WorkflowClient mockWorkflowClient = mock(WorkflowClient.class);
        final OrderQueryHandler handler = new OrderQueryHandler(this.orderProgressionService, mockWorkflowClient, Schedulers.immediate());

        StepVerifier.create(handler.getOrderStatus(sagaId))
                .assertNext(response -> {
                    assertThat(response.orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
                    assertThat(response.sagaProgress()).isEqualTo(OrderSagaProgress.COMPLETED);
                })
                .verifyComplete();

        verifyNoInteractions(mockWorkflowClient);
    }

    @Test
    void getOrderStatusReturnsCompensatedWithoutQueryingTheWorkflowWhenOrderIsCancelled() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder cancelled = CustomerOrder.create(sagaId, "ORDER-2026-900002", Instant.now()).cancel(Instant.now());
        when(this.orderProgressionService.getOrder(sagaId)).thenReturn(Mono.just(cancelled));

        final WorkflowClient mockWorkflowClient = mock(WorkflowClient.class);
        final OrderQueryHandler handler = new OrderQueryHandler(this.orderProgressionService, mockWorkflowClient, Schedulers.immediate());

        StepVerifier.create(handler.getOrderStatus(sagaId))
                .assertNext(response -> {
                    assertThat(response.orderStatus()).isEqualTo(OrderStatus.CANCELLED);
                    assertThat(response.sagaProgress()).isEqualTo(OrderSagaProgress.COMPENSATED);
                })
                .verifyComplete();

        verifyNoInteractions(mockWorkflowClient);
    }

    /**
     * The only case that actually reaches the Workflow: registers {@link FakeOrderSagaWorkflow}
     * (blocks forever on {@code Workflow.await}, so the execution stays open for the query) under
     * the order's {@code businessKey}, then asserts {@link OrderQueryHandler} resolves the response
     * from the live {@code getProgress()} query rather than deriving it from {@code orderStatus}.
     */
    @Test
    void getOrderStatusQueriesTheWorkflowLiveWhenOrderIsPending() {
        this.testEnv = TestWorkflowEnvironment.newInstance();
        final Worker worker = this.testEnv.newWorker(SagaTaskQueues.ORDER_SAGA);
        worker.registerWorkflowImplementationTypes(FakeOrderSagaWorkflow.class);
        this.testEnv.start();

        final String businessKey = "ORDER-2026-900003";
        final OrderSagaWorkflow runningWorkflow = this.testEnv.getWorkflowClient().newWorkflowStub(
                OrderSagaWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(businessKey).setTaskQueue(SagaTaskQueues.ORDER_SAGA).build());
        WorkflowClient.start(runningWorkflow::process,
                new OrderSagaInput(UUID.randomUUID(), businessKey, "SKU-001", 1, BigDecimal.ONE));

        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder pending = CustomerOrder.create(sagaId, businessKey, Instant.now());
        when(this.orderProgressionService.getOrder(sagaId)).thenReturn(Mono.just(pending));

        final OrderQueryHandler handler =
                new OrderQueryHandler(this.orderProgressionService, this.testEnv.getWorkflowClient(), Schedulers.immediate());

        StepVerifier.create(handler.getOrderStatus(sagaId))
                .assertNext(response -> {
                    assertThat(response.orderStatus()).isEqualTo(OrderStatus.PENDING);
                    assertThat(response.sagaProgress()).isEqualTo(OrderSagaProgress.PAYMENT_REQUESTED);
                })
                .verifyComplete();
    }

    /**
     * The order is still PENDING, so the Workflow Execution is expected to exist and be
     * queryable — a failure reaching it (not found, gRPC timeout, ...) is treated as transient
     * from the caller's point of view, wrapped as {@link TemporarySagaException} so
     * {@code RestExceptionHandler}'s existing mapping turns it into a 503 rather than an
     * unshaped 500.
     */
    @Test
    void getOrderStatusWrapsAWorkflowQueryFailureAsATemporarySagaException() {
        final UUID sagaId = UUID.randomUUID();
        final String businessKey = "ORDER-2026-900004";
        final CustomerOrder pending = CustomerOrder.create(sagaId, businessKey, Instant.now());
        when(this.orderProgressionService.getOrder(sagaId)).thenReturn(Mono.just(pending));

        final OrderSagaWorkflow failingWorkflow = mock(OrderSagaWorkflow.class);
        final RuntimeException queryFailure = new RuntimeException("Simulated Temporal query failure");
        when(failingWorkflow.getProgress()).thenThrow(queryFailure);

        final WorkflowClient mockWorkflowClient = mock(WorkflowClient.class);
        when(mockWorkflowClient.newWorkflowStub(OrderSagaWorkflow.class, businessKey)).thenReturn(failingWorkflow);

        final OrderQueryHandler handler = new OrderQueryHandler(this.orderProgressionService, mockWorkflowClient, Schedulers.immediate());

        StepVerifier.create(handler.getOrderStatus(sagaId))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(TemporarySagaException.class);
                    assertThat(error.getCause()).isSameAs(queryFailure);
                })
                .verify();
    }

    @Test
    void getOrderStatusPropagatesOrderNotFoundException() {
        final UUID sagaId = UUID.randomUUID();
        when(this.orderProgressionService.getOrder(sagaId)).thenReturn(Mono.error(new OrderNotFoundException(sagaId)));

        final WorkflowClient mockWorkflowClient = mock(WorkflowClient.class);
        final OrderQueryHandler handler = new OrderQueryHandler(this.orderProgressionService, mockWorkflowClient, Schedulers.immediate());

        StepVerifier.create(handler.getOrderStatus(sagaId))
                .expectError(OrderNotFoundException.class)
                .verify();

        verifyNoInteractions(mockWorkflowClient);
    }

    public static class FakeOrderSagaWorkflow implements OrderSagaWorkflow {

        @Override
        public void process(final OrderSagaInput input) {
            Workflow.await(() -> false);
        }

        @Override
        public OrderSagaProgress getProgress() {
            return OrderSagaProgress.PAYMENT_REQUESTED;
        }
    }
}
