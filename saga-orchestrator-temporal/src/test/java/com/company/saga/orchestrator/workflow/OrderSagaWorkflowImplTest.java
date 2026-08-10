package com.company.saga.orchestrator.workflow;

import com.company.saga.common.workflow.OrderSagaInput;
import com.company.saga.common.workflow.OrderSagaWorkflow;
import com.company.saga.orchestrator.activities.InventoryActivities;
import com.company.saga.orchestrator.activities.OrderActivities;
import com.company.saga.orchestrator.activities.PaymentActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mockito mocks can't stand in for Activity implementations here: the SDK inspects the concrete
 * class handed to {@code registerActivitiesImplementations} and rejects Mockito's generated mock
 * subclass because it (re)carries {@code @ActivityMethod} on its own overridden method, which the
 * SDK only accepts on the interface method. Plain recording fakes (the pattern used by Temporal's
 * own testing docs) avoid that entirely.
 */
class OrderSagaWorkflowImplTest {

    private static final String TASK_QUEUE = "test-order-saga-task-queue";

    private final List<String> callLog = new CopyOnWriteArrayList<>();
    private TestWorkflowEnvironment testEnv;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        final Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderSagaWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new RecordingInventoryActivities(callLog), new RecordingPaymentActivities(callLog), new RecordingOrderActivities(callLog));
        testEnv.start();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void processRunsTheHappyPathStepsInTheSameOrderAsTheCustomOrchestratorsStateMachine() {
        final UUID sagaId = UUID.randomUUID();
        final OrderSagaInput input = new OrderSagaInput(sagaId, "ORDER-2026-800001", "SKU-001", 5, new BigDecimal("49.99"));

        final OrderSagaWorkflow workflow = testEnv.getWorkflowClient().newWorkflowStub(
                OrderSagaWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(input.businessKey()).setTaskQueue(TASK_QUEUE).build());
        workflow.process(input);

        assertThat(callLog).containsExactly(
                "reserveStock(" + sagaId + ",SKU-001,5)",
                "requestPayment(" + sagaId + ",49.99)",
                "confirmOrder(" + sagaId + ")",
                "confirmReservation(" + sagaId + ")");
    }

    private static final class RecordingInventoryActivities implements InventoryActivities {
        private final List<String> callLog;

        private RecordingInventoryActivities(final List<String> callLog) {
            this.callLog = callLog;
        }

        @Override
        public void reserveStock(final UUID sagaId, final String sku, final Integer quantity) {
            callLog.add("reserveStock(" + sagaId + "," + sku + "," + quantity + ")");
        }

        @Override
        public void confirmReservation(final UUID sagaId) {
            callLog.add("confirmReservation(" + sagaId + ")");
        }
    }

    private static final class RecordingPaymentActivities implements PaymentActivities {
        private final List<String> callLog;

        private RecordingPaymentActivities(final List<String> callLog) {
            this.callLog = callLog;
        }

        @Override
        public void requestPayment(final UUID sagaId, final BigDecimal amount) {
            callLog.add("requestPayment(" + sagaId + "," + amount + ")");
        }
    }

    private static final class RecordingOrderActivities implements OrderActivities {
        private final List<String> callLog;

        private RecordingOrderActivities(final List<String> callLog) {
            this.callLog = callLog;
        }

        @Override
        public void confirmOrder(final UUID sagaId) {
            callLog.add("confirmOrder(" + sagaId + ")");
        }
    }
}
