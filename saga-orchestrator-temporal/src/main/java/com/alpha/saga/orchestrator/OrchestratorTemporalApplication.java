package com.alpha.saga.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Temporal Worker hosting the order saga Workflow and its Activities. Unlike
 * every other service in this platform, it owns no database — the saga's own state lives in the
 * Temporal Server, not in a {@code saga_instance}/{@code saga_step} table.
 */
@SpringBootApplication
public class OrchestratorTemporalApplication {

    static void main(final String[] args) {
        SpringApplication.run(OrchestratorTemporalApplication.class, args);
    }
}
