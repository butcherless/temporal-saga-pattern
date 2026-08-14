package com.alpha.saga.common.error;

/**
 * Nature of a failure within the Saga (proposal, section 7.2).
 * {@link #TEMPORARY} errors are retried without changing the Saga's functional state;
 * {@link #PERMANENT} errors trigger the transition to {@code COMPENSATING}.
 */
public enum ErrorType {
    TEMPORARY,
    PERMANENT
}
