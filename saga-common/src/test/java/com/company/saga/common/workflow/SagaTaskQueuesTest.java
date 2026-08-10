package com.company.saga.common.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SagaTaskQueuesTest {

    @Test
    void orderSagaQueueNameIsStable() {
        assertThat(SagaTaskQueues.ORDER_SAGA).isEqualTo("order-saga-task-queue");
    }
}
