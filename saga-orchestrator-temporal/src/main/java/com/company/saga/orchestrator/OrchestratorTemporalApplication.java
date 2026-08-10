package com.company.saga.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Entry point for the Temporal Worker hosting the order saga Workflow and its Activities. Unlike
 * every other service in this platform, it owns no database — the saga's own state lives in the
 * Temporal Server, not in a {@code saga_instance}/{@code saga_step} table.
 */
@Slf4j
@SpringBootApplication
public class OrchestratorTemporalApplication {

    static void main(final String[] args) {
        SpringApplication.run(OrchestratorTemporalApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    void logStartup() {
        log.info("saga-orchestrator-temporal started");
    }
}
