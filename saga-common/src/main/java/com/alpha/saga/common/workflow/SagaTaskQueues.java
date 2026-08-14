package com.alpha.saga.common.workflow;

/**
 * Task Queue names shared between the Workflow starter ({@code order-service}) and the Worker
 * that polls them ({@code saga-orchestrator-temporal}).
 */
public final class SagaTaskQueues {

    public static final String ORDER_SAGA = "order-saga-task-queue";

    private SagaTaskQueues() {
    }
}
