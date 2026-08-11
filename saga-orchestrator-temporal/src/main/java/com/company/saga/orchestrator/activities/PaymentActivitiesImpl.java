package com.company.saga.orchestrator.activities;

import io.temporal.failure.ApplicationFailure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Calls {@code payment-service} directly over HTTP; no Kafka, no Outbox/Inbox involved. */
@Slf4j
@Component
public class PaymentActivitiesImpl implements PaymentActivities {

    private final WebClient paymentWebClient;

    public PaymentActivitiesImpl(@Qualifier("paymentWebClient") final WebClient paymentWebClient) {
        this.paymentWebClient = Objects.requireNonNull(paymentWebClient, "paymentWebClient must not be null");
    }

    @Override
    public void requestPayment(final UUID sagaId, final BigDecimal amount) {
        log.debug("requestPayment - sagaId={}, amount={}", sagaId, amount);
        paymentWebClient.post()
                .uri("/payments")
                .bodyValue(new PaymentChargeRequest(sagaId, amount))
                .retrieve()
                .toBodilessEntity()
                .onErrorMap(WebClientResponseException.class, PaymentActivitiesImpl::toActivityFailure)
                .block();
    }

    /**
     * {@code payment-service} answers a permanent business rejection (proposal §17.3's decline
     * scenario) with 422, mapped by its own {@code RestExceptionHandler} — turned here into a
     * Temporal failure marked non-retryable so the Workflow fails immediately instead of
     * exhausting {@code OrderSagaWorkflowImpl}'s bounded {@code RetryOptions}. Anything else
     * (503, connection errors, ...) is left untouched and keeps retrying as before.
     */
    private static Throwable toActivityFailure(final WebClientResponseException ex) {
        return HttpStatus.UNPROCESSABLE_CONTENT.equals(ex.getStatusCode())
                ? ApplicationFailure.newNonRetryableFailure(ex.getResponseBodyAsString(), "PermanentSagaException")
                : ex;
    }

    @Override
    public void refundPayment(final UUID sagaId) {
        log.debug("refundPayment - sagaId={}", sagaId);
        paymentWebClient.post()
                .uri("/payments/{sagaId}/refund", sagaId)
                .retrieve()
                .toBodilessEntity()
                .onErrorMap(WebClientResponseException.class, PaymentActivitiesImpl::toActivityFailure)
                .block();
    }
}
