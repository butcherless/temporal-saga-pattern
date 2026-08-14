package com.alpha.saga.orchestrator.config;

import io.temporal.worker.WorkerOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualThreadWorkerOptionsCustomizerTest {

    private final VirtualThreadWorkerOptionsCustomizer customizer = new VirtualThreadWorkerOptionsCustomizer();

    /**
     * {@code temporal-sdk:1.37.0} had a copy-paste bug where {@code isUsingVirtualThreadsOnWorkflowWorker()}
     * returned the Activity worker's flag instead of the Workflow worker's own (fixed upstream in
     * 1.38.0, https://github.com/temporalio/sdk-java/pull/2957 — this project pins {@code temporal.version}
     * there specifically so this scoping actually holds). Asserting on it here is what proves
     * {@code setUsingVirtualThreadsOnActivityWorker} is properly scoped and not the blanket
     * {@code setUsingVirtualThreads}, which would also flip Workflow task execution.
     */
    @Test
    void customizeEnablesVirtualThreadsOnlyForTheActivityWorker() {
        final WorkerOptions options = customizer.customize(WorkerOptions.newBuilder(), "orderSagaWorker", "order-saga-task-queue").build();

        assertThat(options.isUsingVirtualThreadsOnActivityWorker()).isTrue();
        assertThat(options.isUsingVirtualThreadsOnWorkflowWorker()).isFalse();
        assertThat(options.isUsingVirtualThreadsOnLocalActivityWorker()).isFalse();
        assertThat(options.isUsingVirtualThreadsOnNexusWorker()).isFalse();
    }

    @Test
    void customizeReturnsTheSameBuilderInstance() {
        final WorkerOptions.Builder builder = WorkerOptions.newBuilder();

        assertThat(customizer.customize(builder, "orderSagaWorker", "order-saga-task-queue")).isSameAs(builder);
    }
}
