package com.company.saga.orchestrator.workflow;

import com.company.saga.common.workflow.OrderSagaInput;
import com.company.saga.common.workflow.OrderSagaWorkflow;
import com.company.saga.orchestrator.activities.InventoryActivities;
import com.company.saga.orchestrator.activities.OrderActivities;
import com.company.saga.orchestrator.activities.PaymentActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * The order saga's happy path (reserve stock → charge payment → confirm the order → confirm the
 * reservation), ported from the custom implementation's {@code SagaStatus} state machine — this
 * is the same step order the proposal's §8.1 reference flow defines, now expressed directly as
 * sequential Activity calls instead of a persisted state machine plus a command-dispatch table.
 * Retry/backoff and compensation on failure are deliberately not implemented yet (see the
 * project's docs for what's still future work) — any Activity failure here simply fails the
 * Workflow Execution.
 */
public class OrderSagaWorkflowImpl implements OrderSagaWorkflow {

    private static final ActivityOptions ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();

    private final InventoryActivities inventoryActivities = Workflow.newActivityStub(InventoryActivities.class, ACTIVITY_OPTIONS);
    private final PaymentActivities paymentActivities = Workflow.newActivityStub(PaymentActivities.class, ACTIVITY_OPTIONS);
    private final OrderActivities orderActivities = Workflow.newActivityStub(OrderActivities.class, ACTIVITY_OPTIONS);

    @Override
    public void process(final OrderSagaInput input) {
        inventoryActivities.reserveStock(input.sagaId(), input.sku(), input.quantity());
        paymentActivities.requestPayment(input.sagaId(), input.amount());
        orderActivities.confirmOrder(input.sagaId());
        inventoryActivities.confirmReservation(input.sagaId());
    }
}
