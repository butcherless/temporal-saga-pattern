package com.company.saga.common.workflow;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Input to {@link OrderSagaWorkflow#process(OrderSagaInput)} — the business payload an order
 * carries through the saga (stock item, quantity, amount to charge), plus the identifiers used
 * for idempotency (`sagaId` as the Workflow Id, `businessKey` for the originating order).
 */
public record OrderSagaInput(UUID sagaId, String businessKey, String sku, Integer quantity, BigDecimal amount) {
}
