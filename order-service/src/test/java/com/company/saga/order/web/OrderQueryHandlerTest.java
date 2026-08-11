package com.company.saga.order.web;

import com.company.saga.common.workflow.OrderSagaInput;
import com.company.saga.common.workflow.OrderSagaProgress;
import com.company.saga.common.workflow.OrderSagaWorkflow;
import com.company.saga.common.workflow.SagaTaskQueues;
import com.company.saga.order.domain.CustomerOrder;
import com.company.saga.order.domain.OrderNotFoundException;
import com.company.saga.order.domain.OrderStatus;
import com.company.saga.order.service.OrderProgressionService;
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
        if (testEnv != null) {
            testEnv.close();
        }
    }

    @Test
    void getOrderStatusReturnsCompletedWithoutQueryingTheWorkflowWhenOrderIsConfirmed() {
        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder confirmed = CustomerOrder.create(sagaId, "ORDER-2026-900001", Instant.now()).confirm(Instant.now());
        when(orderProgressionService.getOrder(sagaId)).thenReturn(Mono.just(confirmed));

        final WorkflowClient mockWorkflowClient = mock(WorkflowClient.class);
        final OrderQueryHandler handler = new OrderQueryHandler(orderProgressionService, mockWorkflowClient, Schedulers.immediate());

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
        when(orderProgressionService.getOrder(sagaId)).thenReturn(Mono.just(cancelled));

        final WorkflowClient mockWorkflowClient = mock(WorkflowClient.class);
        final OrderQueryHandler handler = new OrderQueryHandler(orderProgressionService, mockWorkflowClient, Schedulers.immediate());

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
        testEnv = TestWorkflowEnvironment.newInstance();
        final Worker worker = testEnv.newWorker(SagaTaskQueues.ORDER_SAGA);
        worker.registerWorkflowImplementationTypes(FakeOrderSagaWorkflow.class);
        testEnv.start();

        final String businessKey = "ORDER-2026-900003";
        final OrderSagaWorkflow runningWorkflow = testEnv.getWorkflowClient().newWorkflowStub(
                OrderSagaWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(businessKey).setTaskQueue(SagaTaskQueues.ORDER_SAGA).build());
        WorkflowClient.start(runningWorkflow::process,
                new OrderSagaInput(UUID.randomUUID(), businessKey, "SKU-001", 1, BigDecimal.ONE));

        final UUID sagaId = UUID.randomUUID();
        final CustomerOrder pending = CustomerOrder.create(sagaId, businessKey, Instant.now());
        when(orderProgressionService.getOrder(sagaId)).thenReturn(Mono.just(pending));

        final OrderQueryHandler handler =
                new OrderQueryHandler(orderProgressionService, testEnv.getWorkflowClient(), Schedulers.immediate());

        StepVerifier.create(handler.getOrderStatus(sagaId))
                .assertNext(response -> {
                    assertThat(response.orderStatus()).isEqualTo(OrderStatus.PENDING);
                    assertThat(response.sagaProgress()).isEqualTo(OrderSagaProgress.PAYMENT_REQUESTED);
                })
                .verifyComplete();
    }

    @Test
    void getOrderStatusPropagatesOrderNotFoundException() {
        final UUID sagaId = UUID.randomUUID();
        when(orderProgressionService.getOrder(sagaId)).thenReturn(Mono.error(new OrderNotFoundException(sagaId)));

        final WorkflowClient mockWorkflowClient = mock(WorkflowClient.class);
        final OrderQueryHandler handler = new OrderQueryHandler(orderProgressionService, mockWorkflowClient, Schedulers.immediate());

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
