package com.alpha.saga.orchestrator.activities;

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
        ActivityHttp.post(this.orderWebClient, "/orders/{sagaId}/confirm", sagaId);
    }

    @Override
    public void cancelOrder(final UUID sagaId) {
        log.debug("cancelOrder - sagaId={}", sagaId);
        ActivityHttp.post(this.orderWebClient, "/orders/{sagaId}/cancel", sagaId);
    }
}
