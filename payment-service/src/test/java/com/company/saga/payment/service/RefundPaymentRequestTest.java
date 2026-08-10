package com.company.saga.payment.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundPaymentRequestTest {

    @Test
    void holdsAllProvidedValues() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = Instant.now();

        final RefundPaymentRequest request = new RefundPaymentRequest(sagaId, now);

        assertThat(request.sagaId()).isEqualTo(sagaId);
        assertThat(request.now()).isEqualTo(now);
    }

    @Test
    void rejectsRequiredNulls() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = Instant.now();

        assertThatThrownBy(() -> new RefundPaymentRequest(null, now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RefundPaymentRequest(sagaId, null)).isInstanceOf(NullPointerException.class);
    }
}
