package com.alpha.saga.platform;

import com.alpha.saga.inventory.service.InventoryProgressionService;
import com.alpha.saga.order.service.OrderProgressionService;
import com.alpha.saga.payment.service.PaymentProgressionService;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
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
import java.math.BigDecimal;
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
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * <p>Each test drives one full saga via {@code POST /orders} on {@code order-service}, then
 * asserts on <b>both</b> the Workflow Execution itself (checked via a {@link WorkflowClient}
 * pointed at the same dev server) <b>and</b> the business outcome in {@code order_db}: the happy
 * path ({@code COMPLETED}/{@code CONFIRMED}), proposal §17.3's two temporary-fault scenarios which
 * recover via {@code OrderSagaWorkflowImpl}'s bounded {@code RetryOptions} (same
 * {@code COMPLETED}/{@code CONFIRMED} outcome), its two permanent-fault scenarios, which fail
 * the Workflow immediately — via the non-retryable classification in
 * {@code InventoryActivitiesImpl}/{@code PaymentActivitiesImpl} — cancelling the order via
 * {@code OrderActivities.cancelOrder} so it reaches {@code CANCELLED} instead of staying
 * {@code PENDING}, its duplicate-submission scenario, where a repeat {@code POST /orders} for
 * the same businessKey is a no-op instead of a second saga, and its three compensation scenarios
 * (release-fails, refund-fails, and full rollback), driven by {@code OrderSagaWorkflowImpl}'s
 * {@link io.temporal.workflow.Saga}-based compensation and checked against
 * {@code inventory_db}/{@code payment_db} directly, not just the Workflow's own outcome. The rest
 * of the reference scenarios are explicitly deferred — see the project's docs for what's still
 * future work.
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
        this.postOrderAndAwaitConfirmed("SKU-001", 2, "49.99");
    }

    /**
     * Proposal §17.3's temporary-inventory-fault scenario: {@code InventoryProgressionService}
     * throws {@code TemporarySagaException} on the very first reservation of this SKU, then
     * succeeds — this asserts {@code OrderSagaWorkflowImpl}'s {@code RetryOptions} recovers from
     * it over real HTTP, without the saga ever leaving its normal (non-compensating) path.
     */
    @Test
    void postOrdersRetriesATemporaryInventoryFaultAndStillCompletes() throws Exception {
        this.postOrderAndAwaitConfirmed(InventoryProgressionService.FLAKY_RESERVE_SKU, 2, "49.99");
    }

    /**
     * Proposal §17.3's temporary-payment-fault scenario: an amount at or above
     * {@code FLAKY_THRESHOLD} (but below {@code HARD_DECLINE_THRESHOLD}) times out on the payment
     * gateway's first attempt, then succeeds on retry.
     */
    @Test
    void postOrdersRetriesATemporaryPaymentFaultAndStillCompletes() throws Exception {
        assertThat(PaymentProgressionService.FLAKY_THRESHOLD)
                .isLessThan(new BigDecimal("2000.00"))
                .isLessThan(PaymentProgressionService.HARD_DECLINE_THRESHOLD);

        this.postOrderAndAwaitConfirmed("SKU-001", 2, "2000.00");
    }

    /**
     * Proposal §17.3's insufficient-stock scenario: {@code sku=SKU-001, quantity=999} exceeds the
     * seeded stock of 100, a permanent business rejection — the Workflow must fail on the very
     * first attempt (no retry) instead of exhausting {@code OrderSagaWorkflowImpl}'s bounded
     * {@code RetryOptions} — the order ends up {@code CANCELLED}, not left dangling at
     * {@code PENDING}.
     */
    @Test
    void postOrdersFailsFastOnAPermanentInventoryFailure() throws Exception {
        this.postOrderAndAwaitFailed("SKU-001", 999, "49.99");
    }

    /**
     * Proposal §17.3's hard-decline scenario: an amount at or above {@code HARD_DECLINE_THRESHOLD}
     * is declined outright by the payment gateway, a permanent business rejection.
     */
    @Test
    void postOrdersFailsFastOnAPermanentPaymentDecline() throws Exception {
        assertThat(new BigDecimal("15000.00")).isGreaterThanOrEqualTo(PaymentProgressionService.HARD_DECLINE_THRESHOLD);

        this.postOrderAndAwaitFailed("SKU-001", 2, "15000.00");
    }

    /**
     * Proposal §17.3 scenario 6: stock reserved for {@code PERMANENT_RELEASE_FAILURE_SKU}, then a
     * hard-decline amount fails payment permanently. Compensation tries to release that
     * reservation, and the release itself is the one that fails irrecoverably — the reservation
     * stays {@code RESERVED} instead of {@code RELEASED}.
     */
    @Test
    void postOrdersFailsToReleaseStockAfterAPermanentPaymentDecline() throws Exception {
        assertThat(new BigDecimal("15000.00")).isGreaterThanOrEqualTo(PaymentProgressionService.HARD_DECLINE_THRESHOLD);

        final String businessKey = this.postOrder(InventoryProgressionService.PERMANENT_RELEASE_FAILURE_SKU, 2, "15000.00");
        final UUID sagaId = loadSagaId(businessKey);

        awaitWorkflowFailed(businessKey);
        assertOrderStatus(businessKey, "CANCELLED");
        assertReservationStatus(sagaId, "RESERVED");
    }

    /**
     * Proposal §17.3 scenario 7: payment completes for {@code PERMANENT_REFUND_FAILURE_AMOUNT},
     * then order confirmation fails permanently. Compensation releases the stock successfully, but
     * the refund itself fails irrecoverably — the payment stays {@code COMPLETED} instead of
     * {@code REFUNDED}.
     */
    @Test
    void postOrdersFailsToRefundAfterAPermanentOrderConfirmationFailure() throws Exception {
        final String businessKey = "ORDER-2026-E2E-%s-%s".formatted(
                OrderProgressionService.PERMANENT_CONFIRMATION_FAILURE_MARKER, UUID.randomUUID());
        assertThat(this.postOrderWithBusinessKey(
                businessKey, "SKU-001", 2, PaymentProgressionService.PERMANENT_REFUND_FAILURE_AMOUNT.toPlainString()))
                .isEqualTo(202);
        final UUID sagaId = loadSagaId(businessKey);

        awaitWorkflowFailed(businessKey);
        assertOrderStatus(businessKey, "CANCELLED");
        assertReservationStatus(sagaId, "RELEASED");
        assertPaymentStatus(sagaId, "COMPLETED");
    }

    /**
     * Proposal §17.3 scenario 8: payment completes for a "safe" amount (below any payment fault
     * threshold), then order confirmation fails permanently. Both compensations succeed —
     * reservation {@code RELEASED}, payment {@code REFUNDED} — yet the saga still ends FAILED.
     */
    @Test
    void postOrdersCompensatesFullyAfterAPermanentOrderConfirmationFailure() throws Exception {
        final String businessKey = "ORDER-2026-E2E-%s-%s".formatted(
                OrderProgressionService.PERMANENT_CONFIRMATION_FAILURE_MARKER, UUID.randomUUID());
        assertThat(this.postOrderWithBusinessKey(businessKey, "SKU-001", 2, "250.00")).isEqualTo(202);
        final UUID sagaId = loadSagaId(businessKey);

        awaitWorkflowFailed(businessKey);
        assertOrderStatus(businessKey, "CANCELLED");
        assertReservationStatus(sagaId, "RELEASED");
        assertPaymentStatus(sagaId, "REFUNDED");
    }

    private void postOrderAndAwaitConfirmed(final String sku,
            final int quantity,
            final String amount) throws Exception {
        final String businessKey = this.postOrder(sku, quantity, amount);

        awaitWorkflowCompleted(businessKey);
        awaitOrderStatus(businessKey, "CONFIRMED");
    }

    private void postOrderAndAwaitFailed(final String sku,
            final int quantity,
            final String amount) throws Exception {
        final String businessKey = this.postOrder(sku, quantity, amount);

        awaitWorkflowFailed(businessKey);
        assertOrderStatus(businessKey, "CANCELLED");
    }

    /**
     * Proposal §17.3's duplicate-submission scenario: two sequential {@code POST /orders} for the
     * same businessKey. The second is idempotent — {@code order-service}'s
     * {@code OrderCreationHandler} finds the order the first call already created and skips
     * starting a second Workflow Execution — so only one saga ever runs to completion.
     */
    @Test
    void postOrdersIsIdempotentOnADuplicateBusinessKey() throws Exception {
        final String businessKey = "ORDER-2026-E2E-%s".formatted(UUID.randomUUID());

        assertThat(this.postOrderWithBusinessKey(businessKey, "SKU-001", 2, "49.99")).isEqualTo(202);
        assertThat(this.postOrderWithBusinessKey(businessKey, "SKU-001", 2, "49.99")).isEqualTo(202);

        awaitWorkflowCompleted(businessKey);
        awaitOrderStatus(businessKey, "CONFIRMED");
    }

    private String postOrder(final String sku,
            final int quantity,
            final String amount) throws Exception {
        final String businessKey = "ORDER-2026-E2E-%s".formatted(UUID.randomUUID());
        assertThat(this.postOrderWithBusinessKey(businessKey, sku, quantity, amount)).isEqualTo(202);
        return businessKey;
    }

    private int postOrderWithBusinessKey(final String businessKey,
            final String sku,
            final int quantity,
            final String amount) throws Exception {
        final String requestBody = """
                {"sku":"%s","quantity":%d,"amount":%s,"businessKey":"%s"}""".formatted(sku, quantity, amount, businessKey);

        final HttpClient client = HttpClient.newHttpClient();
        final HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:%d/orders".formatted(ORDER_SERVICE_PORT)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        return response.statusCode();
    }

    private static Process startOrchestrator(final Path logDir) throws IOException {
        final ProcessBuilder builder = execJarProcessBuilder("saga-orchestrator-temporal", logDir);
        builder.environment().put("TEMPORAL_TARGET", temporalTarget());
        return builder.start();
    }

    private static Process startBusinessService(final String module,
            final Path logDir,
            final boolean startsWorkflows) throws IOException {
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

    private static ProcessBuilder execJarProcessBuilder(final String module,
            final Path logDir) {
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
                        HttpRequest.newBuilder(URI.create("http://localhost:%d/actuator/health".formatted(port))).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"UP\"")) {
                    return;
                }
            } catch (final IOException _) {
                // Not up yet — keep polling until the deadline.
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException("Service on port %d did not become healthy within %s".formatted(port, HEALTH_TIMEOUT));
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
                    "Order saga Workflow for businessKey %s did not complete within %s".formatted(businessKey, SAGA_TIMEOUT), error);
        } finally {
            serviceStubs.shutdownNow();
        }
    }

    private static void awaitWorkflowFailed(final String businessKey) {
        final WorkflowServiceStubs serviceStubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(temporalTarget()).build());
        try {
            final WorkflowClient workflowClient = WorkflowClient.newInstance(serviceStubs);
            final WorkflowStub workflowStub = workflowClient.newUntypedWorkflowStub(businessKey);
            assertThatThrownBy(() -> workflowStub.getResult(SAGA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS, Void.class))
                    .isInstanceOf(WorkflowFailedException.class);
        } finally {
            serviceStubs.shutdownNow();
        }
    }

    private static UUID loadSagaId(final String businessKey) throws SQLException {
        return queryOne("order_db", "SELECT id FROM customer_order WHERE business_key = ?", businessKey,
                resultSet -> (UUID) resultSet.getObject("id"));
    }

    private static void assertReservationStatus(final UUID sagaId,
            final String expectedStatus) throws SQLException {
        final String status = queryOne("inventory_db", "SELECT status FROM inventory_reservation WHERE id = ?", sagaId,
                resultSet -> resultSet.getString("status"));
        assertThat(status).isEqualTo(expectedStatus);
    }

    private static void assertPaymentStatus(final UUID sagaId,
            final String expectedStatus) throws SQLException {
        final String status = queryOne("payment_db", "SELECT status FROM payment WHERE id = ?", sagaId,
                resultSet -> resultSet.getString("status"));
        assertThat(status).isEqualTo(expectedStatus);
    }

    private static void assertOrderStatus(final String businessKey,
            final String expectedStatus) throws SQLException {
        final String status = queryOne("order_db", "SELECT status FROM customer_order WHERE business_key = ?", businessKey,
                resultSet -> resultSet.getString("status"));
        assertThat(status).isEqualTo(expectedStatus);
    }

    private static void awaitOrderStatus(final String businessKey,
            final String expectedStatus) throws Exception {
        final Instant deadline = Instant.now().plus(SAGA_TIMEOUT);
        String lastSeenStatus = null;

        while (Instant.now().isBefore(deadline)) {
            final Optional<String> status = queryOptional(
                    "order_db", "SELECT status FROM customer_order WHERE business_key = ?", businessKey,
                    resultSet -> resultSet.getString("status"));
            if (status.isPresent()) {
                lastSeenStatus = status.get();
                if (expectedStatus.equals(lastSeenStatus)) {
                    return;
                }
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new AssertionError("Order for businessKey %s did not reach %s within %s (last seen status: %s)"
                .formatted(businessKey, expectedStatus, SAGA_TIMEOUT, lastSeenStatus));
    }

    /**
     * Shared single-row/single-column query plumbing behind {@code loadSagaId}/{@code assert*Status}/
     * {@code awaitOrderStatus} — each of the three Testcontainers-Postgres databases only ever needs
     * a one-parameter {@code SELECT} by id or business key, so the connection/statement/result-set
     * lifecycle lives here once instead of once per accessor.
     */
    private static <T> T queryOne(final String database,
            final String sql,
            final Object param,
            final ResultSetExtractor<T> extractor)
            throws SQLException {
        return queryOptional(database, sql, param, extractor)
                .orElseThrow(() -> new AssertionError("Query returned no row: %s".formatted(sql)));
    }

    private static <T> Optional<T> queryOptional(
            final String database,
            final String sql,
            final Object param,
            final ResultSetExtractor<T> extractor) throws SQLException {
        final String jdbcUrl = "jdbc:postgresql://%s:%d/%s".formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), database);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, param);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(extractor.extract(resultSet)) : Optional.empty();
            }
        }
    }

    @FunctionalInterface
    private interface ResultSetExtractor<T> {
        T extract(ResultSet resultSet) throws SQLException;
    }
}
