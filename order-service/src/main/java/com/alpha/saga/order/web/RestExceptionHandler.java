package com.alpha.saga.order.web;

import com.alpha.saga.common.error.PermanentSagaException;
import com.alpha.saga.common.error.TemporarySagaException;
import com.alpha.saga.order.domain.OrderNotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * Shapes {@code POST /orders}' error responses as RFC 9457 {@link ProblemDetail} (Step 6 plan's
 * OpenAPI convention). {@link CreateOrderRequestBody}'s compact constructor throws a plain
 * {@link IllegalArgumentException} for invalid input (blank sku, non-positive quantity/amount);
 * WebFlux's body decoding wraps that in a {@link ServerWebInputException} before the controller
 * method is ever reached, so {@link ServerWebInputException#getMostSpecificCause()} is what
 * recovers the original, actually-useful message. A non-validation decode failure (malformed
 * JSON, unknown field) surfaces Jackson's own message verbatim instead — acceptable for this PoC,
 * not distinguished from a validation failure.
 *
 * <p>Overrides {@link ResponseEntityExceptionHandler}'s own {@code handleServerWebInputException}
 * hook rather than adding a competing {@code @ExceptionHandler(ServerWebInputException.class)}:
 * the base class's single umbrella handler already maps that exact exception type, and a second,
 * equally-specific mapping in this subclass is rejected at startup as ambiguous. Everything else
 * stays on the base class's own built-in RFC 9457 handling, with
 * {@code spring.webflux.problemdetails.enabled} as the backstop for whatever neither of these
 * touches.
 *
 * <p>{@link PermanentSagaException}/{@link TemporarySagaException} are mapped explicitly (422/503),
 * same as {@code inventory-service}'s/{@code payment-service}'s own {@code RestExceptionHandler}s —
 * so {@code saga-orchestrator-temporal}'s {@code OrderActivitiesImpl} can tell a definitive
 * business rejection from a transient gateway fault over the wire, the same way it already can for
 * the other two Activities, instead of {@code confirmOrder} retrying a deterministic failure to
 * exhaustion before compensating.
 */
@RestControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected Mono<ResponseEntity<Object>> handleServerWebInputException(
            final ServerWebInputException ex,
            final HttpHeaders headers,
            final HttpStatusCode status,
            final ServerWebExchange exchange) {
        final Throwable cause = ex.getMostSpecificCause();
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, cause.getMessage());
        return this.handleExceptionInternal(ex, problemDetail, headers, status, exchange);
    }

    @ExceptionHandler(PermanentSagaException.class)
    public Mono<ResponseEntity<Object>> handlePermanentSagaException(
            final PermanentSagaException ex,
            final ServerWebExchange exchange) {
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        return this.handleExceptionInternal(ex, problemDetail, new HttpHeaders(), HttpStatus.UNPROCESSABLE_CONTENT, exchange);
    }

    @ExceptionHandler(TemporarySagaException.class)
    public Mono<ResponseEntity<Object>> handleTemporarySagaException(
            final TemporarySagaException ex,
            final ServerWebExchange exchange) {
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        return this.handleExceptionInternal(ex, problemDetail, new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, exchange);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public Mono<ResponseEntity<Object>> handleOrderNotFoundException(
            final OrderNotFoundException ex,
            final ServerWebExchange exchange) {
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        return this.handleExceptionInternal(ex, problemDetail, new HttpHeaders(), HttpStatus.NOT_FOUND, exchange);
    }
}
