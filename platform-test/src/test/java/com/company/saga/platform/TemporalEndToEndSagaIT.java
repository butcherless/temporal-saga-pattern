package com.company.saga.platform;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers-Postgres + Temporal dev server IT — the Temporal analogue of
 * {@code saga-pattern-poc}'s {@code EndToEndSagaIT}. Boots all four services as real, separate
 * forked {@code java -jar} processes (same reason as the custom implementation: each service's
 * own {@code db/migration}/{@code application.yml} live under identical classpath paths inside
 * its own jar, so running them in-process on one shared classpath would make Flyway/Spring Boot's
 * config loading see all four combined instead of each service's own).
 *
 * <p>Unlike the custom implementation, {@code saga-orchestrator-temporal} owns no database of its
 * own — its Testcontainers Postgres only ever needs {@code order_db}/{@code inventory_db}/
 * {@code payment_db}. The Temporal Server itself runs as the officially documented dev server
 * ({@code temporalio/temporal server start-dev}, in-memory, no schema of its own) — a lighter
 * setup than {@code docker-compose.yml}'s production-style {@code temporalio/server} + admin-tools
 * + Postgres, appropriate for a test that tears the whole environment down afterward anyway.
 *
 * <p>Drives one full happy-path saga — {@code POST /orders} on {@code order-service} — then
 * asserts on <b>both</b> the Workflow Execution itself (closes as {@code COMPLETED}, checked via
 * a {@link WorkflowClient} pointed at the same dev server) <b>and</b> the business outcome
 * ({@code customer_order.status} reaches {@code CONFIRMED} in {@code order_db}, same as the
 * custom implementation's own assertion). Retry/backoff, compensation, and the rest of the
 * reference scenarios are explicitly deferred — see the project's docs for what's still future work.
 */
@Testcontainers(disabledWithoutDocker = true)
class TemporalEndToEndSagaIT {

    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SAGA_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private static final int ORCHESTRATOR_PORT = 8080;
    private static final int ORDER_SERVICE_PORT = 8081;
    private static final int INVENTORY_SERVICE_PORT = 8082;
    private static final int PAYMENT_SERVICE_PORT = 8083;
    private static final int TEMPORAL_GRPC_PORT = 7233;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withEnv("POSTGRES_MULTIPLE_DATABASES", "order_db,inventory_db,payment_db")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("init-multiple-databases.sh"),
                    "/docker-entrypoint-initdb.d/init-multiple-databases.sh");

    @Container
    static final GenericContainer<?> TEMPORAL = new GenericContainer<>("temporalio/temporal:1.8.2")
            .withCommand("server", "start-dev", "--ip", "0.0.0.0")
            .withExposedPorts(TEMPORAL_GRPC_PORT);

    private static List<Process> processes;

    @BeforeAll
    static void startAllServices() throws IOException, InterruptedException {
        final Path logDir = Path.of("target", "e2e-logs");
        Files.createDirectories(logDir);

        processes = List.of(
                startOrchestrator(logDir),
                startBusinessService("order-service", logDir, true),
                startBusinessService("inventory-service", logDir, false),
                startBusinessService("payment-service", logDir, false));

        awaitHealthy(ORCHESTRATOR_PORT);
        awaitHealthy(ORDER_SERVICE_PORT);
        awaitHealthy(INVENTORY_SERVICE_PORT);
        awaitHealthy(PAYMENT_SERVICE_PORT);
    }

    @AfterAll
    static void stopAllServices() {
        processes.forEach(Process::destroyForcibly);
    }

    @Test
    void postOrdersDrivesTheFullHappyPathSagaToCompleted() throws Exception {
        final String businessKey = "ORDER-2026-E2E-" + UUID.randomUUID();
        final String requestBody = """
                {"sku":"SKU-001","quantity":2,"amount":49.99,"businessKey":"%s"}""".formatted(businessKey);

        final HttpClient client = HttpClient.newHttpClient();
        final HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + ORDER_SERVICE_PORT + "/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(202);

        awaitWorkflowCompleted(businessKey);
        awaitOrderStatus(businessKey, "CONFIRMED");
    }

    private static Process startOrchestrator(final Path logDir) throws IOException {
        final ProcessBuilder builder = execJarProcessBuilder("saga-orchestrator-temporal", logDir);
        builder.environment().put("TEMPORAL_TARGET", temporalTarget());
        return builder.start();
    }

    private static Process startBusinessService(final String module, final Path logDir, final boolean startsWorkflows) throws IOException {
        final ProcessBuilder builder = execJarProcessBuilder(module, logDir);
        builder.environment().put("DB_HOST", POSTGRES.getHost());
        builder.environment().put("DB_PORT", String.valueOf(POSTGRES.getMappedPort(5432)));
        builder.environment().put("DB_USERNAME", POSTGRES.getUsername());
        builder.environment().put("DB_PASSWORD", POSTGRES.getPassword());
        if (startsWorkflows) {
            builder.environment().put("TEMPORAL_TARGET", temporalTarget());
        }
        return builder.start();
    }

    private static ProcessBuilder execJarProcessBuilder(final String module, final Path logDir) {
        final String version = System.getProperty("saga.platform.version");
        final Path jar = Path.of("..", module, "target", module + "-" + version + "-exec.jar").toAbsolutePath().normalize();
        final ProcessBuilder builder = new ProcessBuilder("java", "-jar", jar.toString());
        builder.redirectOutput(logDir.resolve(module + ".log").toFile());
        builder.redirectErrorStream(true);
        return builder;
    }

    private static String temporalTarget() {
        return TEMPORAL.getHost() + ":" + TEMPORAL.getMappedPort(TEMPORAL_GRPC_PORT);
    }

    private static void awaitHealthy(final int port) throws IOException, InterruptedException {
        final HttpClient client = HttpClient.newHttpClient();
        final Instant deadline = Instant.now().plus(HEALTH_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            try {
                final HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
                    return;
                }
            } catch (final IOException error) {
                // Not up yet — keep polling until the deadline.
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException("Service on port " + port + " did not become healthy within " + HEALTH_TIMEOUT);
    }

    private static void awaitWorkflowCompleted(final String businessKey) {
        final WorkflowServiceStubs serviceStubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(temporalTarget()).build());
        try {
            final WorkflowClient workflowClient = WorkflowClient.newInstance(serviceStubs);
            final WorkflowStub workflowStub = workflowClient.newUntypedWorkflowStub(businessKey);
            workflowStub.getResult(SAGA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS, Void.class);
        } catch (final Exception error) {
            throw new AssertionError(
                    "Order saga Workflow for businessKey " + businessKey + " did not complete within " + SAGA_TIMEOUT, error);
        } finally {
            serviceStubs.shutdownNow();
        }
    }

    private static void awaitOrderStatus(final String businessKey, final String expectedStatus) throws Exception {
        final String jdbcUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/order_db";
        final Instant deadline = Instant.now().plus(SAGA_TIMEOUT);
        String lastSeenStatus = null;

        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                    PreparedStatement statement = connection.prepareStatement("SELECT status FROM customer_order WHERE business_key = ?")) {
                statement.setString(1, businessKey);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        lastSeenStatus = resultSet.getString("status");
                        if (expectedStatus.equals(lastSeenStatus)) {
                            return;
                        }
                    }
                }
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new AssertionError("Order for businessKey " + businessKey + " did not reach " + expectedStatus
                + " within " + SAGA_TIMEOUT + " (last seen status: " + lastSeenStatus + ")");
    }
}
