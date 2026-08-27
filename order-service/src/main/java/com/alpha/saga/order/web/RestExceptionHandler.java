package com.alpha.saga.order.web;

import com.alpha.saga.order.domain.OrderNotFoundException;
import com.alpha.saga.web.AbstractSagaRestExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Maps the order endpoints' errors to RFC 9457 ProblemDetail (see
 * {@link AbstractSagaRestExceptionHandler}), plus {@link OrderNotFoundException} → 404: unlike the
 * shared {@code SagaException} mappings, that one is a legitimate outcome of a caller-supplied
 * {@code sagaId} in {@code GET /orders/{sagaId}}, not an indistinguishable 500.
 */
@RestControllerAdvice
public class RestExceptionHandler extends AbstractSagaRestExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public Mono<ResponseEntity<Object>> handleOrderNotFoundException(
            final OrderNotFoundException ex,
            final ServerWebExchange exchange) {
        return this.toProblemDetail(ex, HttpStatus.NOT_FOUND, exchange);
    }
}
