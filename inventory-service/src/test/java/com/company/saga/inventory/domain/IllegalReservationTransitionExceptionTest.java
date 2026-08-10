package com.company.saga.inventory.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IllegalReservationTransitionExceptionTest {

    @Test
    void exposesFromAndToStatesAndAMessageMentioningBoth() {
        final IllegalReservationTransitionException exception =
                new IllegalReservationTransitionException(ReservationStatus.CONFIRMED, ReservationStatus.RESERVED);

        assertThat(exception.from()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(exception.to()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(exception.getMessage()).contains(ReservationStatus.CONFIRMED.name()).contains(ReservationStatus.RESERVED.name());
        assertThat(exception).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNullFromOrTo() {
        assertThatThrownBy(() -> new IllegalReservationTransitionException(null, ReservationStatus.RESERVED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IllegalReservationTransitionException(ReservationStatus.RESERVED, null))
                .isInstanceOf(NullPointerException.class);
    }
}
