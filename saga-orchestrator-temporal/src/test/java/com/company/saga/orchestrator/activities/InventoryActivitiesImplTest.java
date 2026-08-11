package com.company.saga.orchestrator.activities;

import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class InventoryActivitiesImplTest {

    @Mock
    private ExchangeFunction exchangeFunction;

    private InventoryActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        activities = new InventoryActivitiesImpl(ExchangeFunctionStub.webClientFor(exchangeFunction));
        ExchangeFunctionStub.stubResponse(exchangeFunction, HttpStatus.CREATED);
    }

    @Test
    void reserveStockPostsToTheReservationsEndpoint() {
        final UUID sagaId = UUID.randomUUID();

        activities.reserveStock(sagaId, "SKU-001", 5);

        ExchangeFunctionStub.assertPostedTo(exchangeFunction, "/inventory/reservations");
    }

    @Test
    void reserveStockThrowsANonRetryableFailureWhenStockIsPermanentlyInsufficient() {
        ExchangeFunctionStub.stubResponse(
                exchangeFunction, HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient stock for SKU-001: requested 999, available 100");

        assertThatThrownBy(() -> activities.reserveStock(UUID.randomUUID(), "SKU-001", 999))
                .isInstanceOf(ApplicationFailure.class)
                .extracting(failure -> ((ApplicationFailure) failure).isNonRetryable())
                .isEqualTo(true);
    }

    /**
     * The non-422 half of {@code toActivityFailure}'s branch: a transient gateway fault must
     * pass through unchanged (still a plain {@link WebClientResponseException}, never wrapped as
     * non-retryable) so {@code OrderSagaWorkflowImpl}'s bounded {@code RetryOptions} still retries
     * it, the same as before this classification existed.
     */
    @Test
    void reserveStockRethrowsUnchangedWhenTheFailureIsNotPermanent() {
        ExchangeFunctionStub.stubResponse(
                exchangeFunction, HttpStatus.SERVICE_UNAVAILABLE, "Simulated inventory gateway timeout for sku SKU-INPUTDATA-2");

        assertThatThrownBy(() -> activities.reserveStock(UUID.randomUUID(), "SKU-INPUTDATA-2", 5))
                .isInstanceOf(WebClientResponseException.class)
                .isNotInstanceOf(ApplicationFailure.class);
    }

    @Test
    void confirmReservationPostsToTheConfirmEndpointForTheGivenSagaId() {
        final UUID sagaId = UUID.randomUUID();
        ExchangeFunctionStub.stubResponse(exchangeFunction, HttpStatus.OK);

        activities.confirmReservation(sagaId);

        ExchangeFunctionStub.assertPostedTo(exchangeFunction, "/inventory/reservations/%s/confirm".formatted(sagaId));
    }

    @Test
    void releaseStockPostsToTheReleaseEndpointForTheGivenSagaId() {
        final UUID sagaId = UUID.randomUUID();
        ExchangeFunctionStub.stubResponse(exchangeFunction, HttpStatus.OK);

        activities.releaseStock(sagaId);

        ExchangeFunctionStub.assertPostedTo(exchangeFunction, "/inventory/reservations/%s/release".formatted(sagaId));
    }

    @Test
    void releaseStockThrowsANonRetryableFailureWhenTheReleaseIsPermanentlyUnrecoverable() {
        ExchangeFunctionStub.stubResponse(
                exchangeFunction, HttpStatus.UNPROCESSABLE_CONTENT, "Simulated unrecoverable release failure for sku SKU-INPUTDATA-6");

        assertThatThrownBy(() -> activities.releaseStock(UUID.randomUUID()))
                .isInstanceOf(ApplicationFailure.class)
                .extracting(failure -> ((ApplicationFailure) failure).isNonRetryable())
                .isEqualTo(true);
    }
}
