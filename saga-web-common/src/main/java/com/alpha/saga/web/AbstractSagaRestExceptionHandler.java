package com.alpha.saga.web;

import com.alpha.saga.common.error.PermanentSagaException;
import com.alpha.saga.common.error.TemporarySagaException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * Shared RFC 9457 {@link ProblemDetail} error mapping for every business service's
 * {@code @RestControllerAdvice} (Step 6 plan's OpenAPI convention). Each service subclasses this,
 * adds {@code @RestControllerAdvice}, and contributes only its own extra {@code @ExceptionHandler}
 * methods (e.g. {@code order-service}'s {@code OrderNotFoundException} → 404) via
 * {@link #toProblemDetail}.
 *
 * <p>{@link PermanentSagaException}/{@link TemporarySagaException} are mapped explicitly (422/503)
 * so {@code saga-orchestrator-temporal}'s Activities can tell a definitive business rejection from
 * a transient gateway fault over the wire — without this, both fall through to the
 * {@code spring.webflux.problemdetails.enabled} backstop as an indistinguishable 500.
 *
 * <p>{@link #handleServerWebInputException} is overridden (rather than added as a competing
 * {@code @ExceptionHandler(ServerWebInputException.class)}, which startup rejects as ambiguous
 * against the base class's own umbrella handler): a request-body record's compact constructor
 * throws a plain {@link IllegalArgumentException} for invalid input, WebFlux's body decoding wraps
 * that in a {@link ServerWebInputException} before the controller is reached, and
 * {@link ServerWebInputException#getMostSpecificCause()} is what recovers the original, useful
 * message. A non-validation decode failure (malformed JSON, unknown field) surfaces Jackson's own
 * message verbatim instead — acceptable for this PoC, not distinguished from a validation failure.
 * Everything else stays on the base class's built-in RFC 9457 handling.
 */
public abstract class AbstractSagaRestExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected Mono<ResponseEntity<Object>> handleServerWebInputException(
            final ServerWebInputException ex,
            final HttpHeaders headers,
            final HttpStatusCode status,
            final ServerWebExchange exchange) {
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, ex.getMostSpecificCause().getMessage());
        return this.handleExceptionInternal(ex, problemDetail, headers, status, exchange);
    }

    @ExceptionHandler(PermanentSagaException.class)
    public Mono<ResponseEntity<Object>> handlePermanentSagaException(
            final PermanentSagaException ex,
            final ServerWebExchange exchange) {
        return this.toProblemDetail(ex, HttpStatus.UNPROCESSABLE_CONTENT, exchange);
    }

    @ExceptionHandler(TemporarySagaException.class)
    public Mono<ResponseEntity<Object>> handleTemporarySagaException(
            final TemporarySagaException ex,
            final ServerWebExchange exchange) {
        return this.toProblemDetail(ex, HttpStatus.SERVICE_UNAVAILABLE, exchange);
    }

    /** Shapes {@code ex} as an RFC 9457 {@link ProblemDetail} response at {@code status}. */
    protected Mono<ResponseEntity<Object>> toProblemDetail(
            final Exception ex,
            final HttpStatus status,
            final ServerWebExchange exchange) {
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        return this.handleExceptionInternal(ex, problemDetail, new HttpHeaders(), status, exchange);
    }
}
