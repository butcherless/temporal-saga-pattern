package com.alpha.saga.common.error;

/** Definitive failure (rejected business rule, no stock, payment declined, etc.): triggers compensation. */
public final class PermanentSagaException extends SagaException {

    public PermanentSagaException(final String message) {
        super(ErrorType.PERMANENT, message);
    }

    public PermanentSagaException(final String message,
            final Throwable cause) {
        super(ErrorType.PERMANENT, message, cause);
    }
}
