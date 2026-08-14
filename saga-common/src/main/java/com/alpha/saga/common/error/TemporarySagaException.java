package com.alpha.saga.common.error;

/** Transient failure (timeout, service unavailable, etc.): the orchestrator must retry. */
public final class TemporarySagaException extends SagaException {

    public TemporarySagaException(final String message) {
        super(ErrorType.TEMPORARY, message);
    }

    public TemporarySagaException(final String message,
            final Throwable cause) {
        super(ErrorType.TEMPORARY, message, cause);
    }
}
