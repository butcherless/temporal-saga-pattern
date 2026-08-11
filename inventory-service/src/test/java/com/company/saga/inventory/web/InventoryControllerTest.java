package com.company.saga.inventory.web;

import com.company.saga.common.error.PermanentSagaException;
import com.company.saga.common.error.TemporarySagaException;
import com.company.saga.inventory.domain.IllegalReservationTransitionException;
import com.company.saga.inventory.domain.InventoryReservation;
import com.company.saga.inventory.domain.ReservationStatus;
import com.company.saga.inventory.service.InventoryProgressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryControllerTest {

    @Mock
    private InventoryProgressionService inventoryProgressionService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new InventoryController(inventoryProgressionService))
                .controllerAdvice(new RestExceptionHandler())
                .build();
    }

    @Test
    void reserveStockReturns201WithTheReservation() {
        final UUID sagaId = UUID.randomUUID();
        final InventoryReservation reservation = InventoryReservation.reserve(sagaId, "SKU-001", 5, Instant.now());
        when(inventoryProgressionService.reserveStock(any())).thenReturn(Mono.just(reservation));

        client.post().uri("/inventory/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","sku":"SKU-001","quantity":5}""".formatted(sagaId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.sagaId").isEqualTo(sagaId.toString())
                .jsonPath("$.status").isEqualTo(ReservationStatus.RESERVED.name());
    }

    @Test
    void reserveStockRejectsAnInvalidRequestBodyWithAProblemDetail() {
        final ResponseSpec response = client.post().uri("/inventory/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","sku":"SKU-001","quantity":-1}""".formatted(UUID.randomUUID()))
                .exchange();

        assertProblemDetail(response, 400, "quantity must be positive");
    }

    @Test
    void reserveStockReturns422WhenStockIsPermanentlyInsufficient() {
        final UUID sagaId = UUID.randomUUID();
        when(inventoryProgressionService.reserveStock(any()))
                .thenReturn(Mono.error(new PermanentSagaException("Insufficient stock for SKU-001: requested 999, available 100")));

        final ResponseSpec response = client.post().uri("/inventory/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","sku":"SKU-001","quantity":999}""".formatted(sagaId))
                .exchange();

        assertProblemDetail(response, 422, "Insufficient stock for SKU-001: requested 999, available 100");
    }

    @Test
    void reserveStockReturns503WhenTheGatewayTimesOut() {
        final UUID sagaId = UUID.randomUUID();
        when(inventoryProgressionService.reserveStock(any()))
                .thenReturn(Mono.error(new TemporarySagaException("Simulated inventory gateway timeout for sku SKU-INPUTDATA-2")));

        final ResponseSpec response = client.post().uri("/inventory/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","sku":"SKU-INPUTDATA-2","quantity":5}""".formatted(sagaId))
                .exchange();

        assertProblemDetail(response, 503, "Simulated inventory gateway timeout for sku SKU-INPUTDATA-2");
    }

    @Test
    void confirmReservationReturns200WithTheConfirmedStatus() {
        final UUID sagaId = UUID.randomUUID();
        final InventoryReservation confirmed = InventoryReservation.reserve(sagaId, "SKU-001", 5, Instant.now()).confirm(Instant.now());
        when(inventoryProgressionService.confirmReservation(any())).thenReturn(Mono.just(confirmed));

        client.post().uri("/inventory/reservations/{sagaId}/confirm", sagaId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sagaId").isEqualTo(sagaId.toString())
                .jsonPath("$.status").isEqualTo(ReservationStatus.CONFIRMED.name());
    }

    /**
     * {@link IllegalReservationTransitionException} extends plain {@code IllegalStateException},
     * not {@code SagaException} (an internal invariant violation, not a classified business error
     * — see the exception's own javadoc), and {@code RestExceptionHandler} deliberately doesn't
     * map it: it's meant to surface as an unhandled 500, not a shaped {@code ProblemDetail}.
     */
    @Test
    void confirmReservationReturns500WhenTheReservationCannotLegallyBeConfirmed() {
        final UUID sagaId = UUID.randomUUID();
        when(inventoryProgressionService.confirmReservation(any()))
                .thenReturn(Mono.error(new IllegalReservationTransitionException(ReservationStatus.RELEASED, ReservationStatus.CONFIRMED)));

        client.post().uri("/inventory/reservations/{sagaId}/confirm", sagaId)
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void releaseStockReturns200WithTheReleasedStatus() {
        final UUID sagaId = UUID.randomUUID();
        final InventoryReservation released = InventoryReservation.reserve(sagaId, "SKU-001", 5, Instant.now()).release(Instant.now());
        when(inventoryProgressionService.releaseStock(any())).thenReturn(Mono.just(released));

        client.post().uri("/inventory/reservations/{sagaId}/release", sagaId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sagaId").isEqualTo(sagaId.toString())
                .jsonPath("$.status").isEqualTo(ReservationStatus.RELEASED.name());
    }

    @Test
    void releaseStockReturns422WhenTheReleaseIsPermanentlyUnrecoverable() {
        final UUID sagaId = UUID.randomUUID();
        when(inventoryProgressionService.releaseStock(any()))
                .thenReturn(Mono.error(new PermanentSagaException("Simulated unrecoverable release failure for sku SKU-INPUTDATA-6")));

        final ResponseSpec response = client.post().uri("/inventory/reservations/{sagaId}/release", sagaId).exchange();

        assertProblemDetail(response, 422, "Simulated unrecoverable release failure for sku SKU-INPUTDATA-6");
    }

    @Test
    void creditStockReturns200() {
        final UUID sagaId = UUID.randomUUID();
        when(inventoryProgressionService.creditStock(any())).thenReturn(Mono.empty());

        client.post().uri("/inventory/reservations/credit")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","sku":"SKU-001","quantity":10}""".formatted(sagaId))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void creditStockRejectsAnInvalidRequestBodyWithAProblemDetail() {
        final ResponseSpec response = client.post().uri("/inventory/reservations/credit")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","sku":"SKU-001","quantity":-1}""".formatted(UUID.randomUUID()))
                .exchange();

        assertProblemDetail(response, 400, "quantity must be positive");
    }

    /**
     * Shared shape behind every {@code ProblemDetail} assertion above (400 for bad input, 422/503
     * for the two {@code SagaException} classifications): status code, {@code application/problem+json}
     * content type, and the {@code $.status}/{@code $.detail} body fields.
     */
    private static void assertProblemDetail(final ResponseSpec response,
            final int expectedStatus,
            final String expectedDetail) {
        response.expectStatus().isEqualTo(expectedStatus)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(expectedStatus)
                .jsonPath("$.detail").isEqualTo(expectedDetail);
    }
}
