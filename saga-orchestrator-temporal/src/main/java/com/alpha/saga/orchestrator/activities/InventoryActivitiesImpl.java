package com.alpha.saga.orchestrator.activities;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

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
    public void reserveStock(final UUID sagaId,
            final String sku,
            final Integer quantity) {
        log.debug("reserveStock - sagaId={}, sku={}, quantity={}", sagaId, sku, quantity);
        ActivityHttp.postJson(this.inventoryWebClient, "/inventory/reservations", new InventoryReservationRequest(sagaId, sku, quantity));
    }

    @Override
    public void confirmReservation(final UUID sagaId) {
        log.debug("confirmReservation - sagaId={}", sagaId);
        ActivityHttp.post(this.inventoryWebClient, "/inventory/reservations/{sagaId}/confirm", sagaId);
    }

    @Override
    public void releaseStock(final UUID sagaId) {
        log.debug("releaseStock - sagaId={}", sagaId);
        ActivityHttp.post(this.inventoryWebClient, "/inventory/reservations/{sagaId}/release", sagaId);
    }
}
