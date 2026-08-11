package com.company.saga.payment.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuePartialRefundRequestTest {

    @Test
    void holdsAllProvidedValues() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = Instant.now();

        final IssuePartialRefundRequest request = new IssuePartialRefundRequest(sagaId, new BigDecimal("20.00"), now);

        assertThat(request.sagaId()).isEqualTo(sagaId);
        assertThat(request.amount()).isEqualByComparingTo("20.00");
        assertThat(request.now()).isEqualTo(now);
    }

    @Test
    void rejectsRequiredNulls() {
        final UUID sagaId = UUID.randomUUID();
        final Instant now = Instant.now();

        assertThatThrownBy(() -> new IssuePartialRefundRequest(null, new BigDecimal("20.00"), now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IssuePartialRefundRequest(sagaId, null, now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IssuePartialRefundRequest(sagaId, new BigDecimal("20.00"), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> new IssuePartialRefundRequest(UUID.randomUUID(), BigDecimal.ZERO, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> new IssuePartialRefundRequest(UUID.randomUUID(), new BigDecimal("-1.00"), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }
}
