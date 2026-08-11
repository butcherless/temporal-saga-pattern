package com.company.saga.order.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderNotFoundExceptionTest {

    @Test
    void exposesTheSagaIdAndAMessageMentioningIt() {
        final UUID sagaId = UUID.randomUUID();

        final OrderNotFoundException exception = new OrderNotFoundException(sagaId);

        assertThat(exception.sagaId()).isEqualTo(sagaId);
        assertThat(exception.getMessage()).contains(sagaId.toString());
    }

    @Test
    void rejectsNullSagaId() {
        assertThatThrownBy(() -> new OrderNotFoundException(null)).isInstanceOf(NullPointerException.class);
    }
}
