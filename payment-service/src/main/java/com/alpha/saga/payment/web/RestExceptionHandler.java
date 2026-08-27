package com.alpha.saga.payment.web;

import com.alpha.saga.web.AbstractSagaRestExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps the payment endpoints' errors to RFC 9457 ProblemDetail; see {@link AbstractSagaRestExceptionHandler}. */
@RestControllerAdvice
public class RestExceptionHandler extends AbstractSagaRestExceptionHandler {
}
