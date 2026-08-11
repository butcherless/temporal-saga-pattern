package com.company.saga.order.web;

import com.company.saga.order.persistence.OrderRepository;
import com.company.saga.order.service.OrderProgressionService;
import io.temporal.client.WorkflowClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executors;

/**
 * Wires {@link OrderProgressionService}, {@link OrderCreationHandler}, and
 * {@link OrderQueryHandler}. {@link WorkflowClient} itself comes from
 * {@code temporal-spring-boot-starter}'s own auto-configuration (this service only starts and
 * queries Workflow Executions, it never registers a Worker).
 */
@Configuration
public class OrderWebConfig {

    @Bean
    public OrderProgressionService orderProgressionService(final OrderRepository orderRepository) {
        return new OrderProgressionService(orderRepository);
    }

    /**
     * Backs {@link OrderCreationHandler}'s blocking {@code WorkflowClient.start} bridge (see there
     * for why it can't stay on the WebFlux event loop). A virtual-thread-per-task executor instead
     * of {@code Schedulers.boundedElastic()}'s capped platform-thread pool: that call happens once
     * per {@code POST /orders}, structurally unbounded the same way the Activity Worker's blocking
     * {@code WebClient} calls are (see {@code saga-orchestrator-temporal}'s
     * {@code VirtualThreadWorkerOptionsCustomizer}) — no CPU-bound work, no {@code synchronized}
     * blocks in the path. {@code destroyMethod = "dispose"} releases the executor on shutdown.
     */
    @Bean(destroyMethod = "dispose")
    public Scheduler orderSagaStartScheduler() {
        return Schedulers.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor(), "order-saga-start");
    }

    @Bean
    public OrderCreationHandler orderCreationHandler(
            final OrderProgressionService orderProgressionService,
            final WorkflowClient workflowClient,
            final Scheduler orderSagaStartScheduler) {
        return new OrderCreationHandler(orderProgressionService, workflowClient, orderSagaStartScheduler);
    }

    /**
     * Backs {@link OrderQueryHandler}'s blocking Workflow query, the same bridge shape as
     * {@link #orderCreationHandler}'s {@code WorkflowClient.start} call — reuses
     * {@link #orderSagaStartScheduler} rather than a second bean, since neither call is CPU-bound
     * and query volume doesn't warrant isolating it from the start path at this scale.
     */
    @Bean
    public OrderQueryHandler orderQueryHandler(
            final OrderProgressionService orderProgressionService,
            final WorkflowClient workflowClient,
            final Scheduler orderSagaStartScheduler) {
        return new OrderQueryHandler(orderProgressionService, workflowClient, orderSagaStartScheduler);
    }
}
