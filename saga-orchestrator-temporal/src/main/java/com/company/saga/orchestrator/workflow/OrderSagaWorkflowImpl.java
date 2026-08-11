package com.company.saga.orchestrator.workflow;

import com.company.saga.common.workflow.OrderSagaInput;
import com.company.saga.common.workflow.OrderSagaProgress;
import com.company.saga.common.workflow.OrderSagaWorkflow;
import com.company.saga.orchestrator.activities.InventoryActivities;
import com.company.saga.orchestrator.activities.OrderActivities;
import com.company.saga.orchestrator.activities.PaymentActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * The order saga's happy path (reserve stock → charge payment → confirm the order → confirm the
 * reservation), ported from the custom implementation's {@code SagaStatus} state machine — this
 * is the same step order the proposal's §8.1 reference flow defines, now expressed directly as
 * sequential Activity calls instead of a persisted state machine plus a command-dispatch table.
 * A permanently-failing Activity still fails the Workflow Execution — only bounded by
 * {@link #ACTIVITY_OPTIONS}'s retry cap rather than retrying forever — but now unwinds whatever
 * already succeeded via {@link Saga} first (proposal §17.3 scenarios 6/7/8), and cancels the order
 * on every failure path so it always reaches a terminal state instead of staying {@code PENDING}.
 */
public class OrderSagaWorkflowImpl implements OrderSagaWorkflow {

    /**
     * {@code setMaximumAttempts(3)} is deliberately bounded, not the SDK's default (unlimited)
     * retries: this repo doesn't yet classify every Activity failure as TEMPORARY/PERMANENT (see
     * the project's docs), so without a cap a permanent business failure would retry forever
     * instead of failing the Workflow. Three attempts is enough headroom for the proposal's §17.3
     * temporary-fault scenarios (fail once, succeed on retry).
     */
    private static final ActivityOptions ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMillis(500))
                    .setBackoffCoefficient(2.0)
                    .setMaximumAttempts(3)
                    .build())
            .build();

    private final InventoryActivities inventoryActivities = Workflow.newActivityStub(InventoryActivities.class, ACTIVITY_OPTIONS);
    private final PaymentActivities paymentActivities = Workflow.newActivityStub(PaymentActivities.class, ACTIVITY_OPTIONS);
    private final OrderActivities orderActivities = Workflow.newActivityStub(OrderActivities.class, ACTIVITY_OPTIONS);

    private OrderSagaProgress progress = OrderSagaProgress.STARTED;

    @Override
    public void process(final OrderSagaInput input) {
        // continueWithError: a failed refund (proposal §17.3 scenario 8) must not stop the
        // release compensation from still being attempted afterward.
        final Saga saga = new Saga(new Saga.Options.Builder().setContinueWithError(true).build());
        try {
            inventoryActivities.reserveStock(input.sagaId(), input.sku(), input.quantity());
            saga.addCompensation(inventoryActivities::releaseStock, input.sagaId());
            progress = OrderSagaProgress.INVENTORY_RESERVED;

            paymentActivities.requestPayment(input.sagaId(), input.amount());
            saga.addCompensation(paymentActivities::refundPayment, input.sagaId());
            progress = OrderSagaProgress.PAYMENT_REQUESTED;

            orderActivities.confirmOrder(input.sagaId());
            inventoryActivities.confirmReservation(input.sagaId());
            progress = OrderSagaProgress.COMPLETED;
        } catch (final RuntimeException error) {
            progress = OrderSagaProgress.COMPENSATING;
            // Compensations run in reverse order (refund before release). If they all succeed,
            // saga.compensate() returns normally — the original error is rethrown explicitly so
            // the Workflow Execution still fails instead of looking like it succeeded.
            saga.compensate();
            // Whatever failed, the order itself never reached CONFIRMED — cancelling it here is
            // what turns that dangling PENDING into a correct terminal state, for every failure
            // path (not just the ones that also unwind inventory/payment).
            orderActivities.cancelOrder(input.sagaId());
            progress = OrderSagaProgress.COMPENSATED;
            throw error;
        }
    }

    @Override
    public OrderSagaProgress getProgress() {
        return progress;
    }
}
