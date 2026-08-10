package com.company.saga.inventory.web;

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
        client.post().uri("/inventory/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"sagaId":"%s","sku":"SKU-001","quantity":-1}""".formatted(UUID.randomUUID()))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isEqualTo("quantity must be positive");
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
}
