package com.alpha.saga.order.web;

import com.alpha.saga.common.workflow.OrderSagaInput;
import com.alpha.saga.common.workflow.OrderSagaProgress;
import com.alpha.saga.common.workflow.OrderSagaWorkflow;
import com.alpha.saga.common.workflow.SagaTaskQueues;
import com.alpha.saga.order.domain.CustomerOrder;
import com.alpha.saga.order.service.CreateOrderRequest;
import com.alpha.saga.order.service.OrderProgressionService;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCreationHandlerTest {

    @Mock
    private OrderProgressionService orderProgressionService;

    private TestWorkflowEnvironment testEnv;
    private OrderCreationHandler handler;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        handler = new OrderCreationHandler(orderProgressionService, testEnv.getWorkflowClient(), Schedulers.immediate());
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void createOrderStartsTheSagaWorkflowWithTheOrderPayloadAndBusinessKeyAsWorkflowId() {
        final AtomicReference<OrderSagaInput> receivedInput = new AtomicReference<>();
        registerFakeWorkflow(receivedInput);

        final CreateOrderRequestBody request = new CreateOrderRequestBody("SKU-001", 5, new BigDecimal("49.99"), "ORDER-2026-700001");
        when(orderProgressionService.createOrder(any())).thenAnswer(invocation -> {
            final CreateOrderRequest req = invocation.getArgument(0);
            return Mono.just(CustomerOrder.create(req.sagaId(), req.businessKey(), req.now()));
        });

        StepVerifier.create(handler.createOrder(request))
                .assertNext(response -> assertThat(response.businessKey()).isEqualTo("ORDER-2026-700001"))
                .verifyComplete();

        final WorkflowStub startedWorkflow = testEnv.getWorkflowClient().newUntypedWorkflowStub("ORDER-2026-700001");
        startedWorkflow.getResult(Void.class);

        assertThat(receivedInput.get().businessKey()).isEqualTo("ORDER-2026-700001");
        assertThat(receivedInput.get().sku()).isEqualTo("SKU-001");
        assertThat(receivedInput.get().quantity()).isEqualTo(5);
        assertThat(receivedInput.get().amount()).isEqualByComparingTo("49.99");
    }

    @Test
    void createOrderPropagatesUncaughtErrorsWithoutStartingTheWorkflow() {
        final CreateOrderRequestBody request = new CreateOrderRequestBody("SKU-001", 5, new BigDecimal("49.99"), null);
        when(orderProgressionService.createOrder(any())).thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(handler.createOrder(request))
                .expectError(IllegalStateException.class)
                .verify();
    }

    /**
     * Proposal §17.3's duplicate-submission scenario: {@code orderProgressionService.createOrder}
     * is idempotent by businessKey, so a repeat {@code POST /orders} returns the order it created
     * the first time — with a different {@code id()} than the fresh {@code sagaId} this call
     * minted. That mismatch is the signal {@code startSagaIfNewlyCreated} uses to skip starting a
     * second Workflow Execution entirely.
     */
    @Test
    void createOrderSkipsStartingTheWorkflowWhenTheOrderAlreadyExisted() {
        final WorkflowClient mockWorkflowClient = mock(WorkflowClient.class);
        final OrderCreationHandler handlerWithMockClient =
                new OrderCreationHandler(orderProgressionService, mockWorkflowClient, Schedulers.immediate());

        final CreateOrderRequestBody request = new CreateOrderRequestBody("SKU-001", 5, new BigDecimal("49.99"), "ORDER-2026-700002");
        final CustomerOrder preExistingOrder = CustomerOrder.create(UUID.randomUUID(), "ORDER-2026-700002", Instant.now());
        when(orderProgressionService.createOrder(any())).thenReturn(Mono.just(preExistingOrder));

        StepVerifier.create(handlerWithMockClient.createOrder(request))
                .assertNext(response -> assertThat(response.businessKey()).isEqualTo("ORDER-2026-700002"))
                .verifyComplete();

        verifyNoInteractions(mockWorkflowClient);
    }

    /**
     * Defense against the narrow race where two concurrent duplicate requests both observe no
     * existing order: starts a Workflow Execution for the businessKey directly (standing in for
     * the other request's in-flight saga, kept open via {@code BLOCK_FOREVER_SKU} so it can't
     * complete before this test's own start attempt), then drives {@code createOrder} through the
     * "newly created" branch for the same businessKey. The resulting
     * {@code WorkflowExecutionAlreadyStarted} must be swallowed as an idempotent no-op.
     */
    @Test
    void createOrderSwallowsWorkflowExecutionAlreadyStartedAsAnIdempotentNoOp() {
        final AtomicReference<OrderSagaInput> receivedInput = new AtomicReference<>();
        registerFakeWorkflow(receivedInput);

        final String businessKey = "ORDER-2026-700003";
        final OrderSagaWorkflow alreadyRunning = testEnv.getWorkflowClient().newWorkflowStub(
                OrderSagaWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(businessKey).setTaskQueue(SagaTaskQueues.ORDER_SAGA).build());
        WorkflowClient.start(alreadyRunning::process,
                new OrderSagaInput(UUID.randomUUID(), businessKey, FakeOrderSagaWorkflow.BLOCK_FOREVER_SKU, 5, new BigDecimal("49.99")));

        final CreateOrderRequestBody request = new CreateOrderRequestBody("SKU-001", 5, new BigDecimal("49.99"), businessKey);
        when(orderProgressionService.createOrder(any())).thenAnswer(invocation -> {
            final CreateOrderRequest req = invocation.getArgument(0);
            return Mono.just(CustomerOrder.create(req.sagaId(), req.businessKey(), req.now()));
        });

        StepVerifier.create(handler.createOrder(request))
                .assertNext(response -> assertThat(response.businessKey()).isEqualTo(businessKey))
                .verifyComplete();
    }

    private void registerFakeWorkflow(final AtomicReference<OrderSagaInput> receivedInput) {
        final Worker worker = testEnv.newWorker(SagaTaskQueues.ORDER_SAGA);
        worker.registerWorkflowImplementationTypes(FakeOrderSagaWorkflow.class);
        FakeOrderSagaWorkflow.receivedInput = receivedInput;
        testEnv.start();
    }

    public static class FakeOrderSagaWorkflow implements OrderSagaWorkflow {

        /** Marks a fake execution that never completes, so it stays open for the duration of a test. */
        private static final String BLOCK_FOREVER_SKU = "BLOCK-FOREVER";

        private static AtomicReference<OrderSagaInput> receivedInput;

        @Override
        public void process(final OrderSagaInput input) {
            receivedInput.set(input);
            if (BLOCK_FOREVER_SKU.equals(input.sku())) {
                Workflow.await(() -> false);
            }
        }

        @Override
        public OrderSagaProgress getProgress() {
            return OrderSagaProgress.STARTED;
        }
    }
}
