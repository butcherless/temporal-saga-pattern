package com.alpha.saga.inventory.service;

import com.alpha.saga.common.error.PermanentSagaException;
import com.alpha.saga.common.error.TemporarySagaException;
import com.alpha.saga.inventory.domain.IllegalReservationTransitionException;
import com.alpha.saga.inventory.domain.InventoryReservation;
import com.alpha.saga.inventory.domain.InventoryTestClock;
import com.alpha.saga.inventory.domain.ReservationStatus;
import com.alpha.saga.inventory.domain.StockItem;
import com.alpha.saga.inventory.persistence.InventoryReservationRepository;
import com.alpha.saga.inventory.persistence.StockItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryProgressionServiceTest {

    private static final Instant NOW = InventoryTestClock.FIXED_INSTANT;
    private static final String SKU = "SKU-001";

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private InventoryReservationRepository inventoryReservationRepository;

    private InventoryProgressionService service;

    @BeforeEach
    void setUp() {
        this.service = new InventoryProgressionService(this.stockItemRepository, this.inventoryReservationRepository);
        lenient().when(this.stockItemRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        lenient().when(this.inventoryReservationRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    void reserveStockDebitsStockAndCreatesANewReservation() {
        final UUID sagaId = UUID.randomUUID();
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.empty());
        when(this.stockItemRepository.findById(SKU)).thenReturn(Mono.just(new StockItem(SKU, 100, 0L)));

        StepVerifier.create(this.service.reserveStock(new ReserveStockRequest(sagaId, SKU, 10, NOW)))
                .assertNext(reservation -> {
                    assertThat(reservation.id()).isEqualTo(sagaId);
                    assertThat(reservation.sku()).isEqualTo(SKU);
                    assertThat(reservation.quantity()).isEqualTo(10);
                    assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
                })
                .verifyComplete();

        verify(this.stockItemRepository).save(new StockItem(SKU, 90, 0L));
    }

    @Test
    void reserveStockIsIdempotentAndReturnsTheExistingReservation() {
        final UUID sagaId = UUID.randomUUID();
        final InventoryReservation existing = InventoryReservation.reserve(sagaId, SKU, 10, NOW);
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.just(existing));

        StepVerifier.create(this.service.reserveStock(new ReserveStockRequest(sagaId, SKU, 10, NOW)))
                .assertNext(reservation -> assertThat(reservation).isEqualTo(existing))
                .verifyComplete();

        verify(this.stockItemRepository, never()).findById(any(String.class));
        verify(this.inventoryReservationRepository, never()).save(any());
    }

    @Test
    void reserveStockThrowsWhenStockItemNotFound() {
        final UUID sagaId = UUID.randomUUID();
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.empty());
        when(this.stockItemRepository.findById("SKU-999")).thenReturn(Mono.empty());

        StepVerifier.create(this.service.reserveStock(new ReserveStockRequest(sagaId, "SKU-999", 1, NOW)))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void reserveStockThrowsWhenInsufficientStock() {
        final UUID sagaId = UUID.randomUUID();
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.empty());
        when(this.stockItemRepository.findById("SKU-003")).thenReturn(Mono.just(new StockItem("SKU-003", 0, 0L)));

        StepVerifier.create(this.service.reserveStock(new ReserveStockRequest(sagaId, "SKU-003", 1, NOW)))
                .expectError(PermanentSagaException.class)
                .verify();
    }

    @Test
    void reserveStockThrowsTemporarilyOnFirstAttemptForTheFlakySkuButStillPersists() {
        final UUID sagaId = UUID.randomUUID();
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.empty());
        when(this.stockItemRepository.findById(InventoryProgressionService.FLAKY_RESERVE_SKU)).thenReturn(Mono.just(new StockItem(InventoryProgressionService.FLAKY_RESERVE_SKU, 100, 0L)));

        StepVerifier.create(this.service.reserveStock(new ReserveStockRequest(sagaId, InventoryProgressionService.FLAKY_RESERVE_SKU, 10, NOW)))
                .expectError(TemporarySagaException.class)
                .verify();

        verify(this.stockItemRepository).save(new StockItem(InventoryProgressionService.FLAKY_RESERVE_SKU, 90, 0L));
        verify(this.inventoryReservationRepository).save(InventoryReservation.reserve(sagaId, InventoryProgressionService.FLAKY_RESERVE_SKU, 10, NOW));
    }

    @Test
    void confirmReservationAdvancesFromReservedToConfirmed() {
        final UUID sagaId = UUID.randomUUID();
        final InventoryReservation reserved = InventoryReservation.reserve(sagaId, SKU, 10, NOW);
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.just(reserved));

        StepVerifier.create(this.service.confirmReservation(new ConfirmReservationRequest(sagaId, NOW)))
                .assertNext(reservation -> assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED))
                .verifyComplete();
    }

    @Test
    void confirmReservationIsIdempotentWhenAlreadyConfirmed() {
        final UUID sagaId = UUID.randomUUID();
        final InventoryReservation confirmed = InventoryReservation.reserve(sagaId, SKU, 10, NOW).confirm(NOW);
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.just(confirmed));

        StepVerifier.create(this.service.confirmReservation(new ConfirmReservationRequest(sagaId, NOW)))
                .assertNext(reservation -> assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED))
                .verifyComplete();

        verify(this.inventoryReservationRepository, never()).save(any());
    }

    @Test
    void confirmReservationFailsWhenNotFound() {
        final UUID sagaId = UUID.randomUUID();
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.empty());

        StepVerifier.create(this.service.confirmReservation(new ConfirmReservationRequest(sagaId, NOW)))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void releaseStockCreditsStockAndAdvancesToReleased() {
        final UUID sagaId = UUID.randomUUID();
        final InventoryReservation reserved = InventoryReservation.reserve(sagaId, SKU, 10, NOW);
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.just(reserved));
        when(this.stockItemRepository.findById(SKU)).thenReturn(Mono.just(new StockItem(SKU, 90, 0L)));

        StepVerifier.create(this.service.releaseStock(new ReleaseStockRequest(sagaId, NOW)))
                .assertNext(reservation -> assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED))
                .verifyComplete();

        verify(this.stockItemRepository).save(new StockItem(SKU, 100, 0L));
    }

    @Test
    void releaseStockIsIdempotentWhenAlreadyReleased() {
        final UUID sagaId = UUID.randomUUID();
        final InventoryReservation released = InventoryReservation.reserve(sagaId, SKU, 10, NOW).release(NOW);
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.just(released));

        StepVerifier.create(this.service.releaseStock(new ReleaseStockRequest(sagaId, NOW)))
                .assertNext(reservation -> assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED))
                .verifyComplete();

        verify(this.stockItemRepository, never()).findById(any(String.class));
        verify(this.inventoryReservationRepository, never()).save(any());
    }

    @Test
    void releasingAConfirmedReservationThrowsWithoutTouchingStock() {
        final UUID sagaId = UUID.randomUUID();
        final InventoryReservation confirmed = InventoryReservation.reserve(sagaId, SKU, 10, NOW).confirm(NOW);
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.just(confirmed));

        StepVerifier.create(this.service.releaseStock(new ReleaseStockRequest(sagaId, NOW)))
                .expectError(IllegalReservationTransitionException.class)
                .verify();

        verify(this.stockItemRepository, never()).findById(any(String.class));
    }

    @Test
    void releaseStockThrowsPermanentlyForTheReleaseFailureSkuWithoutTouchingStock() {
        final UUID sagaId = UUID.randomUUID();
        final InventoryReservation reserved = InventoryReservation.reserve(sagaId, InventoryProgressionService.PERMANENT_RELEASE_FAILURE_SKU, 10, NOW);
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.just(reserved));

        StepVerifier.create(this.service.releaseStock(new ReleaseStockRequest(sagaId, NOW)))
                .expectError(PermanentSagaException.class)
                .verify();

        verify(this.stockItemRepository, never()).findById(any(String.class));
        verify(this.inventoryReservationRepository, never()).save(any());
    }

    @Test
    void releaseStockFailsWhenNotFound() {
        final UUID sagaId = UUID.randomUUID();
        when(this.inventoryReservationRepository.findById(sagaId)).thenReturn(Mono.empty());

        StepVerifier.create(this.service.releaseStock(new ReleaseStockRequest(sagaId, NOW)))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void creditStockCreditsTheStockCounterWithoutTouchingAnyReservation() {
        final UUID sagaId = UUID.randomUUID();
        when(this.stockItemRepository.findById(SKU)).thenReturn(Mono.just(new StockItem(SKU, 90, 0L)));

        StepVerifier.create(this.service.creditStock(new CreditStockRequest(sagaId, SKU, 10, NOW)))
                .verifyComplete();

        verify(this.stockItemRepository).save(new StockItem(SKU, 100, 0L));
        verify(this.inventoryReservationRepository, never()).findById(any(UUID.class));
        verify(this.inventoryReservationRepository, never()).save(any());
    }

    @Test
    void creditStockFailsWhenStockItemNotFound() {
        when(this.stockItemRepository.findById("SKU-999")).thenReturn(Mono.empty());

        StepVerifier.create(this.service.creditStock(new CreditStockRequest(UUID.randomUUID(), "SKU-999", 10, NOW)))
                .expectError(IllegalStateException.class)
                .verify();
    }
}
