package com.company.saga.payment.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IllegalPaymentTransitionExceptionTest {

    @Test
    void exposesFromAndToStatesAndAMessageMentioningBoth() {
        final IllegalPaymentTransitionException exception =
                new IllegalPaymentTransitionException(PaymentStatus.FAILED, PaymentStatus.COMPLETED);

        assertThat(exception.from()).isEqualTo(PaymentStatus.FAILED);
        assertThat(exception.to()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(exception.getMessage()).contains(PaymentStatus.FAILED.name()).contains(PaymentStatus.COMPLETED.name());
        assertThat(exception).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNullFromOrTo() {
        assertThatThrownBy(() -> new IllegalPaymentTransitionException(null, PaymentStatus.COMPLETED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IllegalPaymentTransitionException(PaymentStatus.COMPLETED, null))
                .isInstanceOf(NullPointerException.class);
    }
}
