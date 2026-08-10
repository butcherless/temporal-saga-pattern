package com.company.saga.common.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SagaExceptionTest {

    @Test
    void temporarySagaException_isClassifiedAsTemporary() {
        TemporarySagaException exception = new TemporarySagaException("timeout calling Inventory Service");

        assertThat(exception.errorType()).isEqualTo(ErrorType.TEMPORARY);
        assertThat(exception.getMessage()).isEqualTo("timeout calling Inventory Service");
        assertThat(exception).isInstanceOf(SagaException.class);
    }

    @Test
    void permanentSagaException_isClassifiedAsPermanent() {
        PermanentSagaException exception = new PermanentSagaException("payment declined by the provider");

        assertThat(exception.errorType()).isEqualTo(ErrorType.PERMANENT);
        assertThat(exception.getMessage()).isEqualTo("payment declined by the provider");
    }

    @Test
    void preservesRootCause() {
        RuntimeException rootCause = new RuntimeException("connection reset");

        TemporarySagaException exception = new TemporarySagaException("timeout calling Payment Service", rootCause);

        assertThat(exception.getCause()).isSameAs(rootCause);
    }
}
