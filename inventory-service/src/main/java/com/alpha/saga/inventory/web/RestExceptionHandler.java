package com.alpha.saga.inventory.web;

import com.alpha.saga.common.error.PermanentSagaException;
import com.alpha.saga.common.error.TemporarySagaException;
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
 * Shapes the reservation endpoints' error responses as RFC 9457 {@link ProblemDetail}, same
 * convention and same {@code ServerWebInputException} recovery as {@code order-service}'s own
 * {@code RestExceptionHandler} — see there for the full rationale.
 *
 * <p>{@link PermanentSagaException}/{@link TemporarySagaException} are mapped explicitly (422/503)
 * so {@code saga-orchestrator-temporal}'s Activities can tell a definitive business rejection from
 * a transient gateway fault over the wire — without this, both fall through to the
 * {@code spring.webflux.problemdetails.enabled} backstop as an indistinguishable 500.
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
        return handleExceptionInternal(ex, problemDetail, headers, status, exchange);
    }

    @ExceptionHandler(PermanentSagaException.class)
    public Mono<ResponseEntity<Object>> handlePermanentSagaException(
            final PermanentSagaException ex,
            final ServerWebExchange exchange) {
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        return handleExceptionInternal(ex, problemDetail, new HttpHeaders(), HttpStatus.UNPROCESSABLE_CONTENT, exchange);
    }

    @ExceptionHandler(TemporarySagaException.class)
    public Mono<ResponseEntity<Object>> handleTemporarySagaException(
            final TemporarySagaException ex,
            final ServerWebExchange exchange) {
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        return handleExceptionInternal(ex, problemDetail, new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, exchange);
    }
}
