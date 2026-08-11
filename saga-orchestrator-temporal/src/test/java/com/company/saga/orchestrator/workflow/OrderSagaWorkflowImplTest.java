package com.company.saga.orchestrator.workflow;

import com.company.saga.common.workflow.OrderSagaInput;
import com.company.saga.common.workflow.OrderSagaWorkflow;
import com.company.saga.orchestrator.activities.InventoryActivities;
import com.company.saga.orchestrator.activities.OrderActivities;
import com.company.saga.orchestrator.activities.PaymentActivities;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mockito mocks can't stand in for Activity implementations here: the SDK inspects the concrete
 * class handed to {@code registerActivitiesImplementations} and rejects Mockito's generated mock
 * subclass because it (re)carries {@code @ActivityMethod} on its own overridden method, which the
 * SDK only accepts on the interface method. A plain recording fake (the pattern used by Temporal's
 * own testing docs) avoids that entirely — {@link FakeActivities} implements all three Activity
 * interfaces at once and is configured per test via its {@link FakeActivities.Builder}, since a
 * single object implementing multiple {@code @ActivityInterface}s registers all of them in one
 * {@code registerActivitiesImplementations} call.
 */
class OrderSagaWorkflowImplTest {

    private static final String TASK_QUEUE = "test-order-saga-task-queue";

    private final List<String> callLog = new CopyOnWriteArrayList<>();
    private TestWorkflowEnvironment testEnv;

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void processRunsTheHappyPathStepsInTheSameOrderAsTheCustomOrchestratorsStateMachine() {
        final UUID sagaId = UUID.randomUUID();
        final OrderSagaInput input = new OrderSagaInput(sagaId, "ORDER-2026-800001", "SKU-001", 5, new BigDecimal("49.99"));

        final OrderSagaWorkflow workflow = newWorkflowStub(input.businessKey(), FakeActivities.builder(callLog).build());
        workflow.process(input);

        assertThat(callLog).containsExactly(
                "reserveStock(%s,SKU-001,5)".formatted(sagaId),
                "requestPayment(%s,49.99)".formatted(sagaId),
                "confirmOrder(%s)".formatted(sagaId),
                "confirmReservation(%s)".formatted(sagaId));
    }

    /**
     * Exercises {@code OrderSagaWorkflowImpl}'s {@code RetryOptions} directly against a fake that
     * fails the first {@code reserveStock} call and succeeds the second — the same shape as
     * proposal §17.3's temporary-inventory-fault scenario — without going through real HTTP, so
     * the retry itself (not {@code InventoryActivitiesImpl}'s error mapping) is what's under test.
     */
    @Test
    void processRetriesATemporaryInventoryFaultOnceAndStillCompletesTheSaga() {
        final UUID sagaId = UUID.randomUUID();
        final OrderSagaInput input = new OrderSagaInput(sagaId, "ORDER-2026-800002", "SKU-INPUTDATA-2", 5, new BigDecimal("49.99"));

        final OrderSagaWorkflow workflow = newWorkflowStub(
                input.businessKey(), FakeActivities.builder(callLog).failReserveStockOnFirstAttempt().build());
        workflow.process(input);

        assertThat(callLog).containsExactly(
                "reserveStock(%s,SKU-INPUTDATA-2,5)".formatted(sagaId),
                "reserveStock(%s,SKU-INPUTDATA-2,5)".formatted(sagaId),
                "requestPayment(%s,49.99)".formatted(sagaId),
                "confirmOrder(%s)".formatted(sagaId),
                "confirmReservation(%s)".formatted(sagaId));
    }

    /**
     * Exercises the non-retryable classification directly: a fake that always throws
     * {@link ApplicationFailure#newNonRetryableFailure} (what {@code InventoryActivitiesImpl} now
     * maps a 422 to) — same shape as proposal §17.3's insufficient-stock scenario. Asserts the
     * Workflow fails on the very first attempt (no retry consumed) instead of exhausting
     * {@code OrderSagaWorkflowImpl}'s bounded {@code RetryOptions}.
     */
    @Test
    void processFailsImmediatelyOnAPermanentInventoryFailureWithoutRetrying() {
        final UUID sagaId = UUID.randomUUID();
        final OrderSagaInput input = new OrderSagaInput(sagaId, "ORDER-2026-800003", "SKU-001", 999, new BigDecimal("49.99"));

        final OrderSagaWorkflow workflow = newWorkflowStub(
                input.businessKey(), FakeActivities.builder(callLog).failReserveStockPermanently().build());

        assertThatThrownBy(() -> workflow.process(input)).isInstanceOf(WorkflowFailedException.class);
        assertThat(callLog).containsExactly(
                "reserveStock(%s,SKU-001,999)".formatted(sagaId),
                "cancelOrder(%s)".formatted(sagaId));
    }

    /**
     * Exercises {@code Saga}'s reverse-order compensation directly: {@code confirmOrder} fails
     * non-retryably after both {@code reserveStock} and {@code requestPayment} already succeeded —
     * same shape as proposal §17.3 scenario 8. Both compensations succeed here, so this also
     * proves the original failure is still what fails the Workflow Execution, not swallowed by a
     * clean compensation.
     */
    @Test
    void processCompensatesInReverseOrderWhenAFinalStepFailsPermanently() {
        final UUID sagaId = UUID.randomUUID();
        final OrderSagaInput input = new OrderSagaInput(sagaId, "ORDER-2026-800004", "SKU-001", 5, new BigDecimal("49.99"));

        final OrderSagaWorkflow workflow = newWorkflowStub(
                input.businessKey(), FakeActivities.builder(callLog).failConfirmOrderPermanently().build());

        assertThatThrownBy(() -> workflow.process(input)).isInstanceOf(WorkflowFailedException.class);
        assertThat(callLog).containsExactly(
                "reserveStock(%s,SKU-001,5)".formatted(sagaId),
                "requestPayment(%s,49.99)".formatted(sagaId),
                "confirmOrder(%s)".formatted(sagaId),
                "refundPayment(%s)".formatted(sagaId),
                "releaseStock(%s)".formatted(sagaId),
                "cancelOrder(%s)".formatted(sagaId));
    }

    /**
     * Exercises {@code Saga.Options.setContinueWithError(true)}: the refund compensation fails
     * non-retryably (proposal §17.3 scenario 7's shape), but the release compensation registered
     * before it must still run instead of being skipped.
     */
    @Test
    void processContinuesCompensatingWhenAnEarlierCompensationFails() {
        final UUID sagaId = UUID.randomUUID();
        final OrderSagaInput input = new OrderSagaInput(sagaId, "ORDER-2026-800005", "SKU-001", 5, new BigDecimal("49.99"));

        final OrderSagaWorkflow workflow = newWorkflowStub(
                input.businessKey(),
                FakeActivities.builder(callLog).failRefundPaymentPermanently().failConfirmOrderPermanently().build());

        assertThatThrownBy(() -> workflow.process(input)).isInstanceOf(WorkflowFailedException.class);
        assertThat(callLog).containsExactly(
                "reserveStock(%s,SKU-001,5)".formatted(sagaId),
                "requestPayment(%s,49.99)".formatted(sagaId),
                "confirmOrder(%s)".formatted(sagaId),
                "refundPayment(%s)".formatted(sagaId),
                "releaseStock(%s)".formatted(sagaId),
                "cancelOrder(%s)".formatted(sagaId));
    }

    private OrderSagaWorkflow newWorkflowStub(final String businessKey, final FakeActivities activities) {
        testEnv = TestWorkflowEnvironment.newInstance();
        final Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderSagaWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        testEnv.start();
        return testEnv.getWorkflowClient().newWorkflowStub(
                OrderSagaWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(businessKey).setTaskQueue(TASK_QUEUE).build());
    }

    /**
     * A single recording+fault-injecting fake covering all three Activity interfaces. None of this
     * class's tests assert on failure messages, only on the {@code callLog} sequence and on whether
     * the Workflow ultimately fails — so every permanent fault below can share one generic message.
     */
    private static final class FakeActivities implements InventoryActivities, PaymentActivities, OrderActivities {

        private final List<String> callLog;
        private final boolean reserveStockFailsOnFirstAttempt;
        private final boolean reserveStockFailsPermanently;
        private final boolean refundPaymentFailsPermanently;
        private final boolean confirmOrderFailsPermanently;
        private final AtomicInteger reserveStockAttempts = new AtomicInteger();

        private FakeActivities(final Builder builder) {
            this.callLog = builder.callLog;
            this.reserveStockFailsOnFirstAttempt = builder.reserveStockFailsOnFirstAttempt;
            this.reserveStockFailsPermanently = builder.reserveStockFailsPermanently;
            this.refundPaymentFailsPermanently = builder.refundPaymentFailsPermanently;
            this.confirmOrderFailsPermanently = builder.confirmOrderFailsPermanently;
        }

        static Builder builder(final List<String> callLog) {
            return new Builder(callLog);
        }

        @Override
        public void reserveStock(final UUID sagaId, final String sku, final Integer quantity) {
            callLog.add("reserveStock(%s,%s,%s)".formatted(sagaId, sku, quantity));
            if (reserveStockFailsPermanently) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "Simulated permanent inventory failure for sku %s".formatted(sku), "PermanentSagaException");
            }
            if (reserveStockFailsOnFirstAttempt && reserveStockAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Simulated inventory gateway timeout for sku %s".formatted(sku));
            }
        }

        @Override
        public void confirmReservation(final UUID sagaId) {
            callLog.add("confirmReservation(%s)".formatted(sagaId));
        }

        @Override
        public void releaseStock(final UUID sagaId) {
            callLog.add("releaseStock(%s)".formatted(sagaId));
        }

        @Override
        public void requestPayment(final UUID sagaId, final BigDecimal amount) {
            callLog.add("requestPayment(%s,%s)".formatted(sagaId, amount));
        }

        @Override
        public void refundPayment(final UUID sagaId) {
            callLog.add("refundPayment(%s)".formatted(sagaId));
            if (refundPaymentFailsPermanently) {
                throw ApplicationFailure.newNonRetryableFailure("Simulated permanent refund failure", "PermanentSagaException");
            }
        }

        @Override
        public void confirmOrder(final UUID sagaId) {
            callLog.add("confirmOrder(%s)".formatted(sagaId));
            if (confirmOrderFailsPermanently) {
                throw ApplicationFailure.newNonRetryableFailure("Simulated permanent order confirmation failure", "PermanentSagaException");
            }
        }

        @Override
        public void cancelOrder(final UUID sagaId) {
            callLog.add("cancelOrder(%s)".formatted(sagaId));
        }

        private static final class Builder {
            private final List<String> callLog;
            private boolean reserveStockFailsOnFirstAttempt;
            private boolean reserveStockFailsPermanently;
            private boolean refundPaymentFailsPermanently;
            private boolean confirmOrderFailsPermanently;

            private Builder(final List<String> callLog) {
                this.callLog = callLog;
            }

            Builder failReserveStockOnFirstAttempt() {
                this.reserveStockFailsOnFirstAttempt = true;
                return this;
            }

            Builder failReserveStockPermanently() {
                this.reserveStockFailsPermanently = true;
                return this;
            }

            Builder failRefundPaymentPermanently() {
                this.refundPaymentFailsPermanently = true;
                return this;
            }

            Builder failConfirmOrderPermanently() {
                this.confirmOrderFailsPermanently = true;
                return this;
            }

            FakeActivities build() {
                return new FakeActivities(this);
            }
        }
    }
}
