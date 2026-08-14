package com.alpha.saga.order.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateOrderRequestTest {

    private static final String BUSINESS_KEY = "ORDER-2026-000001";

    @Test
    void holdsAllProvidedValues() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = Instant.now();

        final CreateOrderRequest request = new CreateOrderRequest(sagaId, BUSINESS_KEY, now);

        assertThat(request.sagaId()).isEqualTo(sagaId);
        assertThat(request.businessKey()).isEqualTo(BUSINESS_KEY);
        assertThat(request.now()).isEqualTo(now);
    }

    @Test
    void rejectsRequiredNulls() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = Instant.now();

        assertThatThrownBy(() -> new CreateOrderRequest(null, BUSINESS_KEY, now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CreateOrderRequest(sagaId, null, now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CreateOrderRequest(sagaId, BUSINESS_KEY, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankBusinessKey() {
        assertThatThrownBy(() -> new CreateOrderRequest(UUID.randomUUID(), " ", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("businessKey");
    }
}
