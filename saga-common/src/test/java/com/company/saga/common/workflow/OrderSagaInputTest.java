package com.company.saga.common.workflow;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSagaInputTest {

    @Test
    void exposesAllComponentsPassedToTheConstructor() {
        final UUID sagaId = UUID.randomUUID();

        final OrderSagaInput input = new OrderSagaInput(sagaId, "biz-key-1", "SKU-1", 3, new BigDecimal("19.99"));

        assertThat(input.sagaId()).isEqualTo(sagaId);
        assertThat(input.businessKey()).isEqualTo("biz-key-1");
        assertThat(input.sku()).isEqualTo("SKU-1");
        assertThat(input.quantity()).isEqualTo(3);
        assertThat(input.amount()).isEqualTo(new BigDecimal("19.99"));
    }
}
