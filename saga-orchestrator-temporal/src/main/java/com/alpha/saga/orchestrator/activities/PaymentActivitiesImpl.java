package com.alpha.saga.orchestrator.activities;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

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
    public void requestPayment(final UUID sagaId,
            final BigDecimal amount) {
        log.debug("requestPayment - sagaId={}, amount={}", sagaId, amount);
        ActivityHttp.postJson(this.paymentWebClient, "/payments", new PaymentChargeRequest(sagaId, amount));
    }

    @Override
    public void refundPayment(final UUID sagaId) {
        log.debug("refundPayment - sagaId={}", sagaId);
        ActivityHttp.post(this.paymentWebClient, "/payments/{sagaId}/refund", sagaId);
    }
}
