package com.company.saga.order.web;

import com.company.saga.common.workflow.OrderSagaInput;
import com.company.saga.common.workflow.OrderSagaWorkflow;
import com.company.saga.common.workflow.SagaTaskQueues;
import com.company.saga.order.domain.CustomerOrder;
import com.company.saga.order.service.CreateOrderRequest;
import com.company.saga.order.service.OrderProgressionService;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        handler = new OrderCreationHandler(orderProgressionService, testEnv.getWorkflowClient());
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

    private void registerFakeWorkflow(final AtomicReference<OrderSagaInput> receivedInput) {
        final Worker worker = testEnv.newWorker(SagaTaskQueues.ORDER_SAGA);
        worker.registerWorkflowImplementationTypes(FakeOrderSagaWorkflow.class);
        FakeOrderSagaWorkflow.receivedInput = receivedInput;
        testEnv.start();
    }

    public static class FakeOrderSagaWorkflow implements OrderSagaWorkflow {

        private static AtomicReference<OrderSagaInput> receivedInput;

        @Override
        public void process(final OrderSagaInput input) {
            receivedInput.set(input);
        }
    }
}
