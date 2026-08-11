package com.company.saga.common.error;

import java.util.Objects;

/** Base of every business exception within the Saga; always classified by {@link ErrorType}. */
public abstract class SagaException extends RuntimeException {

    private final ErrorType errorType;

    protected SagaException(final ErrorType errorType,
            final String message) {
        super(message);
        this.errorType = Objects.requireNonNull(errorType, "errorType must not be null");
    }

    protected SagaException(final ErrorType errorType,
            final String message,
            final Throwable cause) {
        super(message, cause);
        this.errorType = Objects.requireNonNull(errorType, "errorType must not be null");
    }

    public ErrorType errorType() {
        return errorType;
    }
}
