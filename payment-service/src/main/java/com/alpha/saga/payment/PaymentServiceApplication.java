package com.alpha.saga.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Entry point for the Payment service.
 *
 * <p>Re-imports {@link DataSourceAutoConfiguration}: Spring Boot backs it off by default once an
 * R2DBC {@code ConnectionFactory} is present, but Flyway still needs it to build its own JDBC
 * connection from {@code spring.flyway.url} at startup.
 */
@SpringBootApplication
@Import(DataSourceAutoConfiguration.class)
public class PaymentServiceApplication {

    static void main(final String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
