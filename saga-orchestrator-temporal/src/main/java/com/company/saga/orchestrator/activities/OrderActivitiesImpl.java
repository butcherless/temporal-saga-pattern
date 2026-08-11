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
                .onErrorMap(WebClientResponseException.class, OrderActivitiesImpl::toActivityFailure)
                .block();
    }

    /**
     * {@code order-service} answers a permanent business rejection (proposal §17.3's
     * confirmation-failure scenario) with 422, mapped by its own {@code RestExceptionHandler} —
     * turned here into a Temporal failure marked non-retryable so the Workflow fails immediately
     * and starts compensating instead of exhausting {@code OrderSagaWorkflowImpl}'s bounded
     * {@code RetryOptions} on a failure that can never succeed. Anything else (503, connection
     * errors, ...) is left untouched and keeps retrying as before.
     */
    private static Throwable toActivityFailure(final WebClientResponseException ex) {
        return HttpStatus.UNPROCESSABLE_CONTENT.equals(ex.getStatusCode())
                ? ApplicationFailure.newNonRetryableFailure(ex.getResponseBodyAsString(), "PermanentSagaException")
                : ex;
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
