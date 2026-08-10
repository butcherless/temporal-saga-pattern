package com.company.saga.inventory.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReserveStockRequestTest {

    private static final String SKU = "SKU-001";

    @Test
    void holdsAllProvidedValues() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = Instant.now();

        final ReserveStockRequest request = new ReserveStockRequest(sagaId, SKU, 5, now);

        assertThat(request.sagaId()).isEqualTo(sagaId);
        assertThat(request.sku()).isEqualTo(SKU);
        assertThat(request.quantity()).isEqualTo(5);
        assertThat(request.now()).isEqualTo(now);
    }

    @Test
    void rejectsRequiredNulls() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = Instant.now();

        assertThatThrownBy(() -> new ReserveStockRequest(null, SKU, 5, now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReserveStockRequest(sagaId, null, 5, now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReserveStockRequest(sagaId, SKU, null, now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReserveStockRequest(sagaId, SKU, 5, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankSku() {
        assertThatThrownBy(() -> new ReserveStockRequest(UUID.randomUUID(), " ", 5, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku");
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> new ReserveStockRequest(UUID.randomUUID(), SKU, 0, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
        assertThatThrownBy(() -> new ReserveStockRequest(UUID.randomUUID(), SKU, -1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
    }
}
