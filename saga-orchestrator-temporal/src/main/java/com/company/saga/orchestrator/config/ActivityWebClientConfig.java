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
 *
 * <p>Built from the injected, auto-configured {@link WebClient.Builder} — not the static
 * {@link WebClient#create(String)} factory, which bypasses auto-configuration and every
 * {@code WebClientCustomizer} entirely (Spring Boot 4.1's own {@code WebClientAutoConfiguration}
 * javadoc). This module declares {@code micrometer-tracing-bridge-otel}/
 * {@code micrometer-registry-prometheus} specifically so these Activity→business-service calls
 * are observable; going through {@code WebClient.create(...)} meant that instrumentation was
 * silently doing nothing for the saga's actual HTTP traffic. Connect/read timeouts come from
 * {@code spring.http.clients.*} in {@code application.yml} (Boot 4.1's unified HTTP client
 * settings), applied here the same way for the same reason.
 */
@Configuration
public class ActivityWebClientConfig {

    @Bean
    public WebClient orderWebClient(final WebClient.Builder webClientBuilder, @Value("${services.order.base-url}") final String baseUrl) {
        return webClientBuilder.baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient inventoryWebClient(
            final WebClient.Builder webClientBuilder, @Value("${services.inventory.base-url}") final String baseUrl) {
        return webClientBuilder.baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient paymentWebClient(
            final WebClient.Builder webClientBuilder, @Value("${services.payment.base-url}") final String baseUrl) {
        return webClientBuilder.baseUrl(baseUrl).build();
    }
}
