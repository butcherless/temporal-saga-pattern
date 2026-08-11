package com.company.saga.inventory.web;

import com.company.saga.inventory.service.ConfirmReservationRequest;
import com.company.saga.inventory.service.InventoryProgressionService;
import com.company.saga.inventory.service.ReleaseStockRequest;
import com.company.saga.inventory.service.ReserveStockRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** REST entry points for the reservation lifecycle, called synchronously by the order saga Workflow's InventoryActivities. */
@RestController
@RequestMapping("/inventory/reservations")
@Tag(name = "Inventory reservations", description = "Reserve and confirm stock for a saga")
@Slf4j
public class InventoryController {

    private final InventoryProgressionService inventoryProgressionService;

    public InventoryController(final InventoryProgressionService inventoryProgressionService) {
        this.inventoryProgressionService = Objects.requireNonNull(inventoryProgressionService, "inventoryProgressionService must not be null");
    }

    @PostMapping
    @Operation(
            summary = "Reserve stock",
            description = "Debits the requested quantity from the stock item and creates the reservation. Idempotent by sagaId.")
    @ApiResponse(responseCode = "201", description = "Reservation created (or already existed)")
    @ApiResponse(responseCode = "400", description = "Invalid request body (blank sku, non-positive quantity)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public Mono<ResponseEntity<ReservationResponseBody>> reserveStock(@RequestBody final ReserveStockRequestBody request) {
        log.debug("reserveStock - {}", request);

        return inventoryProgressionService.reserveStock(new ReserveStockRequest(request.sagaId(), request.sku(), request.quantity(), Instant.now()))
                .map(reservation -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(new ReservationResponseBody(reservation.id(), reservation.status())));
    }

    @PostMapping("/{sagaId}/confirm")
    @Operation(
            summary = "Confirm a reservation",
            description = "Called once payment has completed, so the reserved stock is no longer reversible. Idempotent by sagaId.")
    @ApiResponse(responseCode = "200", description = "Reservation confirmed (or already was)")
    public Mono<ResponseEntity<ReservationResponseBody>> confirmReservation(
            @PathVariable("sagaId") @Parameter(description = "The saga id, also the reservation's id") final UUID sagaId) {
        log.debug("confirmReservation - {}", sagaId);

        return inventoryProgressionService.confirmReservation(new ConfirmReservationRequest(sagaId, Instant.now()))
                .map(reservation -> ResponseEntity.ok(new ReservationResponseBody(reservation.id(), reservation.status())));
    }

    @PostMapping("/{sagaId}/release")
    @Operation(
            summary = "Release a reservation",
            description = "Compensation: called when a later saga step permanently fails, crediting the stock back. Idempotent by sagaId.")
    @ApiResponse(responseCode = "200", description = "Reservation released (or already was)")
    public Mono<ResponseEntity<ReservationResponseBody>> releaseStock(
            @PathVariable("sagaId") @Parameter(description = "The saga id, also the reservation's id") final UUID sagaId) {
        log.debug("releaseStock - {}", sagaId);

        return inventoryProgressionService.releaseStock(new ReleaseStockRequest(sagaId, Instant.now()))
                .map(reservation -> ResponseEntity.ok(new ReservationResponseBody(reservation.id(), reservation.status())));
    }
}
