package com.company.saga.common.workflow;

/**
 * The order saga's own progress, as exposed by {@link OrderSagaWorkflow#getProgress()} — lets a
 * caller poll {@code GET /orders/{sagaId}} for live step-level detail while the saga is still
 * {@code PENDING}, without a {@code saga_step}-style read model table. Mirrors
 * {@code OrderSagaWorkflowImpl.process()}'s actual step sequence: reserve stock, request payment,
 * confirm the order and its reservation; on failure, compensate then cancel.
 */
public enum OrderSagaProgress {

    STARTED,
    INVENTORY_RESERVED,
    PAYMENT_REQUESTED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED
}
