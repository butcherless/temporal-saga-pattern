package com.company.saga.orchestrator.activities;

import io.temporal.failure.ApplicationFailure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Objects;
import java.util.UUID;

/** Calls {@code inventory-service} directly over HTTP; no Kafka, no Outbox/Inbox involved. */
@Slf4j
@Component
public class InventoryActivitiesImpl implements InventoryActivities {

    private final WebClient inventoryWebClient;

    public InventoryActivitiesImpl(@Qualifier("inventoryWebClient") final WebClient inventoryWebClient) {
        this.inventoryWebClient = Objects.requireNonNull(inventoryWebClient, "inventoryWebClient must not be null");
    }

    @Override
    public void reserveStock(final UUID sagaId, final String sku, final Integer quantity) {
        log.debug("reserveStock - sagaId={}, sku={}, quantity={}", sagaId, sku, quantity);
        inventoryWebClient.post()
                .uri("/inventory/reservations")
                .bodyValue(new InventoryReservationRequest(sagaId, sku, quantity))
                .retrieve()
                .toBodilessEntity()
                .onErrorMap(WebClientResponseException.class, InventoryActivitiesImpl::toActivityFailure)
                .block();
    }

    /**
     * {@code inventory-service} answers a permanent business rejection (proposal §17.3's
     * insufficient-stock scenario) with 422, mapped by its own {@code RestExceptionHandler} —
     * turned here into a Temporal failure marked non-retryable so the Workflow fails immediately
     * instead of exhausting {@code OrderSagaWorkflowImpl}'s bounded {@code RetryOptions}. Anything
     * else (503, connection errors, ...) is left untouched and keeps retrying as before.
     */
    private static Throwable toActivityFailure(final WebClientResponseException ex) {
        return HttpStatus.UNPROCESSABLE_CONTENT.equals(ex.getStatusCode())
                ? ApplicationFailure.newNonRetryableFailure(ex.getResponseBodyAsString(), "PermanentSagaException")
                : ex;
    }

    @Override
    public void confirmReservation(final UUID sagaId) {
        log.debug("confirmReservation - sagaId={}", sagaId);
        inventoryWebClient.post()
                .uri("/inventory/reservations/{sagaId}/confirm", sagaId)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    @Override
    public void releaseStock(final UUID sagaId) {
        log.debug("releaseStock - sagaId={}", sagaId);
        inventoryWebClient.post()
                .uri("/inventory/reservations/{sagaId}/release", sagaId)
                .retrieve()
                .toBodilessEntity()
                .onErrorMap(WebClientResponseException.class, InventoryActivitiesImpl::toActivityFailure)
                .block();
    }
}
