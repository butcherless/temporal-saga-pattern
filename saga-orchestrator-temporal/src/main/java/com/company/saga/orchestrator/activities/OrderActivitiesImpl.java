package com.company.saga.orchestrator.activities;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Objects;
import java.util.UUID;

/** Calls {@code order-service} directly over HTTP; no Kafka, no Outbox/Inbox involved. */
@Slf4j
@Component
public class OrderActivitiesImpl implements OrderActivities {

    private final WebClient orderWebClient;

    public OrderActivitiesImpl(@Qualifier("orderWebClient") final WebClient orderWebClient) {
        this.orderWebClient = Objects.requireNonNull(orderWebClient, "orderWebClient must not be null");
    }

    @Override
    public void confirmOrder(final UUID sagaId) {
        log.debug("confirmOrder - sagaId={}", sagaId);
        orderWebClient.post()
                .uri("/orders/{sagaId}/confirm", sagaId)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    @Override
    public void cancelOrder(final UUID sagaId) {
        log.debug("cancelOrder - sagaId={}", sagaId);
        orderWebClient.post()
                .uri("/orders/{sagaId}/cancel", sagaId)
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
