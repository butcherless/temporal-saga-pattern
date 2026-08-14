package com.alpha.saga.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * Binds the {@code services.*.base-url} block from {@code application.yml} — one typed bean
 * instead of three independent {@code @Value("${services.*.base-url}")} placeholders, so a
 * missing or malformed entry fails at binding time against the full property path, not as three
 * unrelated string lookups each failing (or silently defaulting) on their own.
 */
@ConfigurationProperties(prefix = "services")
public record ServicesProperties(Service order,
        Service inventory,
        Service payment) {

    public ServicesProperties {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(inventory, "inventory must not be null");
        Objects.requireNonNull(payment, "payment must not be null");
    }

    public record Service(String baseUrl) {

        public Service {
            Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        }
    }
}
