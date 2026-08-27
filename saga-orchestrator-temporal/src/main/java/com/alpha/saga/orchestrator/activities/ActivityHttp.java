package com.alpha.saga.orchestrator.activities;

import io.temporal.failure.ApplicationFailure;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The one shape every {@code *ActivitiesImpl} call has: a blocking {@code POST} to a business
 * service that returns no body, with a business rejection (HTTP 422, mapped by that service's
 * {@code RestExceptionHandler}) turned into a non-retryable Temporal {@link ApplicationFailure}
 * so {@code OrderSagaWorkflowImpl}'s bounded {@code RetryOptions} fails fast instead of exhausting
 * attempts on a deterministic failure. Anything else (503, connection errors, ...) is left
 * untouched and keeps retrying.
 */
final class ActivityHttp {

    private ActivityHttp() {
    }

    /** {@code POST uri} with a JSON body, no response body expected. */
    static void postJson(final WebClient client,
            final String uri,
            final Object body) {
        client.post()
                .uri(uri)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .onErrorMap(WebClientResponseException.class, ActivityHttp::toActivityFailure)
                .block();
    }

    /** {@code POST uriTemplate} (expanded with {@code uriVars}) with no body either way. */
    static void post(final WebClient client,
            final String uriTemplate,
            final Object... uriVars) {
        client.post()
                .uri(uriTemplate, uriVars)
                .retrieve()
                .toBodilessEntity()
                .onErrorMap(WebClientResponseException.class, ActivityHttp::toActivityFailure)
                .block();
    }

    private static Throwable toActivityFailure(final WebClientResponseException ex) {
        return HttpStatus.UNPROCESSABLE_CONTENT.equals(ex.getStatusCode())
                ? ApplicationFailure.newNonRetryableFailure(ex.getResponseBodyAsString(), "PermanentSagaException")
                : ex;
    }
}
