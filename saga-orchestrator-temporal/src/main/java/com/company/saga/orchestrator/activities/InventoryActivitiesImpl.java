package com.company.saga.orchestrator.activities;

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
    public void reserveStock(final UUID sagaId, final String sku, final Integer quantity) {
        log.debug("reserveStock - sagaId={}, sku={}, quantity={}", sagaId, sku, quantity);
        inventoryWebClient.post()
                .uri("/inventory/reservations")
                .bodyValue(new InventoryReservationRequest(sagaId, sku, quantity))
                .retrieve()
                .toBodilessEntity()
                .block();
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
}
