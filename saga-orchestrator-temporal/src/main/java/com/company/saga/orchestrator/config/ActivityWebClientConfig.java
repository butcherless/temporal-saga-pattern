package com.company.saga.orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per business service, each pointed at that service's own base URL —
 * disambiguated by bean name (the project's own convention where autowiring-by-parameter-name
 * isn't available; see {@code order-service}'s {@code transactionalOperator}/nested-transaction
 * beans for the same pattern).
 */
@Configuration
public class ActivityWebClientConfig {

    @Bean
    public WebClient orderWebClient(@Value("${services.order.base-url}") final String baseUrl) {
        return WebClient.create(baseUrl);
    }

    @Bean
    public WebClient inventoryWebClient(@Value("${services.inventory.base-url}") final String baseUrl) {
        return WebClient.create(baseUrl);
    }

    @Bean
    public WebClient paymentWebClient(@Value("${services.payment.base-url}") final String baseUrl) {
        return WebClient.create(baseUrl);
    }
}
