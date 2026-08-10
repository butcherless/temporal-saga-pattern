package com.company.saga.common.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Contract shared between whoever starts the order saga ({@code order-service}) and the Worker
 * that executes it ({@code saga-orchestrator-temporal}) — the Temporal analogue of the
 * {@code OrderCreatedEvent} that used to trigger the custom orchestrator.
 */
@WorkflowInterface
public interface OrderSagaWorkflow {

    @WorkflowMethod
    void process(OrderSagaInput input);
}
