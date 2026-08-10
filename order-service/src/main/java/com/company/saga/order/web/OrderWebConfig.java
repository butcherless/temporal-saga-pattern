package com.company.saga.order.web;

import com.company.saga.order.persistence.OrderRepository;
import com.company.saga.order.service.OrderProgressionService;
import io.temporal.client.WorkflowClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link OrderProgressionService} and {@link OrderCreationHandler}. {@link WorkflowClient}
 * itself comes from {@code temporal-spring-boot-starter}'s own auto-configuration (this service
 * only starts Workflow Executions, it never registers a Worker).
 */
@Configuration
public class OrderWebConfig {

    @Bean
    public OrderProgressionService orderProgressionService(final OrderRepository orderRepository) {
        return new OrderProgressionService(orderRepository);
    }

    @Bean
    public OrderCreationHandler orderCreationHandler(final OrderProgressionService orderProgressionService, final WorkflowClient workflowClient) {
        return new OrderCreationHandler(orderProgressionService, workflowClient);
    }
}
