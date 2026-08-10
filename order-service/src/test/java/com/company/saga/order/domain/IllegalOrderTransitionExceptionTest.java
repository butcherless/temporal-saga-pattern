package com.company.saga.order.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IllegalOrderTransitionExceptionTest {

    @Test
    void exposesFromAndToStatesAndAMessageMentioningBoth() {
        final IllegalOrderTransitionException exception =
                new IllegalOrderTransitionException(OrderStatus.CONFIRMED, OrderStatus.PENDING);

        assertThat(exception.from()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(exception.to()).isEqualTo(OrderStatus.PENDING);
        assertThat(exception.getMessage()).contains(OrderStatus.CONFIRMED.name()).contains(OrderStatus.PENDING.name());
        assertThat(exception).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNullFromOrTo() {
        assertThatThrownBy(() -> new IllegalOrderTransitionException(null, OrderStatus.PENDING))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IllegalOrderTransitionException(OrderStatus.PENDING, null))
                .isInstanceOf(NullPointerException.class);
    }
}
