package com.company.saga.inventory.service;

import com.company.saga.common.error.PermanentSagaException;
import com.company.saga.common.error.TemporarySagaException;
import com.company.saga.inventory.domain.IllegalReservationTransitionException;
import com.company.saga.inventory.domain.InventoryReservation;
import com.company.saga.inventory.domain.ReservationStatus;
import com.company.saga.inventory.domain.StockItem;
import com.company.saga.inventory.persistence.InventoryReservationRepository;
import com.company.saga.inventory.persistence.StockItemRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Inventory Service's idempotent use cases (proposal, section 10.5): reserve, confirm, and
 * release a reservation. {@code reserveStock} and {@code releaseStock} span two repositories
 * (debiting/crediting {@link StockItem} alongside the {@link InventoryReservation} itself) —
 * the R2DBC {@code @Transactional} boundary that would make each pair atomic against a real
 * connection is deferred to the infrastructure phase (Step 5 plan). There is no real warehouse
 * gateway in this PoC: two fixed SKUs recognized by {@link #simulateReserveFault}/
 * {@link #simulateReleaseFault} stand in for proposal §17.3 scenarios 2 and 6 (mirroring
 * {@code PaymentProgressionService}'s own deterministic fake gateway), so those can be exercised
 * end-to-end with fixed input data rather than real fault injection.
 */
@Slf4j
public class InventoryProgressionService {

    /**
     * PoC-only, not a real business rule: proposal §17.3 scenario 2 — times out (TEMPORARY) on the first reservation attempt, then succeeds.
     * Public (not private) so tests can reference it directly instead of retyping the literal.
     */
    public static final String FLAKY_RESERVE_SKU = "SKU-INPUTDATA-2";

    /**
     * PoC-only, not a real business rule: proposal §17.3 scenario 6 — releasing this SKU's reservation always fails (PERMANENT), unrecoverable.
     * Public (not private) so tests can reference it directly instead of retyping the literal.
     */
    public static final String PERMANENT_RELEASE_FAILURE_SKU = "SKU-INPUTDATA-6";

    private final StockItemRepository stockItemRepository;
    private final InventoryReservationRepository inventoryReservationRepository;

    public InventoryProgressionService(
            final StockItemRepository stockItemRepository,
            final InventoryReservationRepository inventoryReservationRepository) {
        this.stockItemRepository = Objects.requireNonNull(stockItemRepository, "stockItemRepository must not be null");
        this.inventoryReservationRepository =
                Objects.requireNonNull(inventoryReservationRepository, "inventoryReservationRepository must not be null");
    }

    /**
     * Reserves {@code request.quantity()} units of {@code request.sku()}, or returns the existing
     * reservation if {@code request.sagaId()} already has one.
     *
     * @throws PermanentSagaException if not enough stock is available
     * @throws TemporarySagaException on the first attempt to reserve {@link #FLAKY_RESERVE_SKU} (PoC fault injection)
     */
    public Mono<InventoryReservation> reserveStock(final ReserveStockRequest request) {
        log.debug("reserveStock - {}", request);
        return inventoryReservationRepository.findById(request.sagaId())
                .switchIfEmpty(Mono.defer(() -> debitAndReserve(request)
                        .flatMap(reservation -> simulateReserveFault(request.sku(), reservation))));
    }

    /**
     * Confirms the reservation, or returns it unchanged if already {@link ReservationStatus#CONFIRMED}.
     *
     * @throws IllegalReservationTransitionException if the reservation cannot legally be confirmed (e.g. already {@code RELEASED})
     */
    public Mono<InventoryReservation> confirmReservation(final ConfirmReservationRequest request) {
        log.debug("confirmReservation - {}", request);
        return loadReservation(request.sagaId())
                .flatMap(reservation -> reservation.status() == ReservationStatus.CONFIRMED
                        ? Mono.just(reservation)
                        : inventoryReservationRepository.save(reservation.confirm(request.now())));
    }

    /**
     * Releases the reservation and credits the stock back (compensation), or returns the
     * reservation unchanged if already {@link ReservationStatus#RELEASED}.
     *
     * @throws IllegalReservationTransitionException if the reservation cannot legally be released (e.g. already {@code CONFIRMED} — proposal §9.3's {@code PIVOT} rule)
     * @throws PermanentSagaException every time {@link #PERMANENT_RELEASE_FAILURE_SKU}'s reservation is released (PoC fault injection)
     */
    public Mono<InventoryReservation> releaseStock(final ReleaseStockRequest request) {
        log.debug("releaseStock - {}", request);
        return loadReservation(request.sagaId())
                .flatMap(reservation -> reservation.status() == ReservationStatus.RELEASED
                        ? Mono.just(reservation)
                        : simulateReleaseFault(reservation)
                                .then(Mono.defer(() -> creditStockAndRelease(reservation, request.now()))));
    }

    private Mono<InventoryReservation> debitAndReserve(final ReserveStockRequest request) {
        return loadStockItem(request.sku())
                .map(stockItem -> stockItem.reserve(request.quantity()))
                .flatMap(stockItemRepository::save)
                .then(inventoryReservationRepository.save(
                        InventoryReservation.reserve(request.sagaId(), request.sku(), request.quantity(), request.now())));
    }

    private Mono<InventoryReservation> creditStockAndRelease(final InventoryReservation reservation,
            final Instant now) {
        final InventoryReservation released = reservation.release(now);
        return loadStockItem(reservation.sku())
                .map(stockItem -> stockItem.release(reservation.quantity()))
                .flatMap(stockItemRepository::save)
                .then(inventoryReservationRepository.save(released));
    }

    /**
     * PoC fault injection for §17.3 scenario 2: throws on the very first successful reservation
     * of {@link #FLAKY_RESERVE_SKU} (this method only runs once per sagaId — a retry's
     * {@code reserveStock} call finds the already-persisted reservation via {@code findById} and
     * returns it directly, without ever reaching this method again).
     */
    private Mono<InventoryReservation> simulateReserveFault(final String sku,
            final InventoryReservation reservation) {
        return switch (sku) {
            case FLAKY_RESERVE_SKU ->
                    Mono.error(new TemporarySagaException("Simulated inventory gateway timeout for sku %s".formatted(sku)));
            default -> Mono.just(reservation);
        };
    }

    /** PoC fault injection for §17.3 scenario 6: always throws before releasing {@link #PERMANENT_RELEASE_FAILURE_SKU}'s stock. */
    private Mono<Void> simulateReleaseFault(final InventoryReservation reservation) {
        return switch (reservation.sku()) {
            case PERMANENT_RELEASE_FAILURE_SKU ->
                    Mono.error(new PermanentSagaException("Simulated unrecoverable release failure for sku %s".formatted(reservation.sku())));
            default -> Mono.empty();
        };
    }

    private Mono<InventoryReservation> loadReservation(final UUID sagaId) {
        return inventoryReservationRepository.findById(sagaId)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("Reservation not found: %s".formatted(sagaId))));
    }

    private Mono<StockItem> loadStockItem(final String sku) {
        return stockItemRepository.findById(sku)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("Stock item not found: %s".formatted(sku))));
    }
}
