package com.company.saga.payment.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestPaymentRequestTest {

    @Test
    void holdsAllProvidedValues() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = Instant.now();

        final RequestPaymentRequest request = new RequestPaymentRequest(sagaId, new BigDecimal("49.99"), now);

        assertThat(request.sagaId()).isEqualTo(sagaId);
        assertThat(request.amount()).isEqualByComparingTo("49.99");
        assertThat(request.now()).isEqualTo(now);
    }

    @Test
    void rejectsRequiredNulls() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = Instant.now();

        assertThatThrownBy(() -> new RequestPaymentRequest(null, new BigDecimal("49.99"), now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestPaymentRequest(sagaId, null, now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestPaymentRequest(sagaId, new BigDecimal("49.99"), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> new RequestPaymentRequest(UUID.randomUUID(), BigDecimal.ZERO, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> new RequestPaymentRequest(UUID.randomUUID(), new BigDecimal("-1.00"), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }
}
