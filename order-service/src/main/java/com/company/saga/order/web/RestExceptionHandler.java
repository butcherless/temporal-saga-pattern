package com.company.saga.order.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
}
