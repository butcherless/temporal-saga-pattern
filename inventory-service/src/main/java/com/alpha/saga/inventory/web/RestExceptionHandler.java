package com.alpha.saga.inventory.web;

import com.alpha.saga.web.AbstractSagaRestExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps the reservation endpoints' errors to RFC 9457 ProblemDetail; see {@link AbstractSagaRestExceptionHandler}. */
@RestControllerAdvice
public class RestExceptionHandler extends AbstractSagaRestExceptionHandler {
}
