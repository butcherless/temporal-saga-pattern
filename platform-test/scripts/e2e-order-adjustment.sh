#!/usr/bin/env bash
#
# ==============================================================================================
# Scenario: partial-quantity order adjustment ops — creditStock and issuePartialRefund
# (docs/order-adjustment-and-status-query-plan.md, Part A)
#
# Business context: a quantity-decrease adjustment for an already-completed order needs to credit
# stock back and refund an amount without ever touching the original order's InventoryReservation/
# Payment rows — those are 1:1 with the original saga and, for inventory, the reservation's
# CONFIRMED state is a terminal PIVOT with no reversal path. This scenario exercises the two new,
# standalone REST use cases directly — inventory-service's POST /inventory/reservations/credit and
# payment-service's POST /payments/partial-refunds — since the adjustment saga/workflow that would
# call them together as one unit is a separate, later piece of work (see the plan doc).
#
# Trigger used here: sku=SKU-001 (100 units seeded by Flyway) credited back by 10; a partial refund
# of 20.00 for a freshly minted adjustment sagaId.
#
# Expected result: stock_item.available_quantity for SKU-001 increases by exactly 10; a single
# partial_refund row exists for the adjustment sagaId with amount 20.00, and re-issuing the same
# sagaId is a no-op (idempotent), not a second row.
# ==============================================================================================
#
# Curl-based end-to-end test, without JUnit/Maven driving it. Complements the unit tests
# (InventoryProgressionServiceTest#creditStock*, PaymentProgressionServiceTest#issuePartialRefund*)
# with a plain-bash version against the real docker-compose stack. Every run recreates
# Postgres/Temporal from scratch and rebuilds the five module jars, so it is slow (a few minutes)
# but self-contained and reproducible.
#
# Usage: platform-test/scripts/e2e-order-adjustment.sh

SCENARIO_NAME="order-adjustment"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

bootstrap_environment

SKU="SKU-001"
ADJUSTMENT_SAGA_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')

log "Reading stock_item.available_quantity for $SKU before crediting..."
BEFORE_QUANTITY=$(psql_query inventory_db "SELECT available_quantity FROM stock_item WHERE sku = '$SKU'")
log "available_quantity before = $BEFORE_QUANTITY"

log "POST /inventory/reservations/credit (sagaId=$ADJUSTMENT_SAGA_ID, sku=$SKU, quantity=10)..."
CREDIT_HTTP_CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "http://localhost:$(port_for_module inventory-service)/inventory/reservations/credit" \
    -H 'Content-Type: application/json' \
    -d "{\"sagaId\":\"$ADJUSTMENT_SAGA_ID\",\"sku\":\"$SKU\",\"quantity\":10}")
assert_status "POST /inventory/reservations/credit http code" "$CREDIT_HTTP_CODE" "200"

AFTER_QUANTITY=$(psql_query inventory_db "SELECT available_quantity FROM stock_item WHERE sku = '$SKU'")
EXPECTED_QUANTITY=$((BEFORE_QUANTITY + 10))
assert_status "available_quantity after credit" "$AFTER_QUANTITY" "$EXPECTED_QUANTITY"

log "POST /payments/partial-refunds (sagaId=$ADJUSTMENT_SAGA_ID, amount=20.00)..."
REFUND_RESPONSE=$(curl -sS -w '\n%{http_code}' -X POST "http://localhost:$(port_for_module payment-service)/payments/partial-refunds" \
    -H 'Content-Type: application/json' \
    -d "{\"sagaId\":\"$ADJUSTMENT_SAGA_ID\",\"amount\":20.00}")
REFUND_HTTP_CODE=$(echo "$REFUND_RESPONSE" | tail -n1)
REFUND_BODY=$(echo "$REFUND_RESPONSE" | sed '$d')
assert_status "POST /payments/partial-refunds http code" "$REFUND_HTTP_CODE" "201"

REFUND_AMOUNT=$(echo "$REFUND_BODY" | grep -oE '"amount"[[:space:]]*:[[:space:]]*[0-9.]+' | grep -oE '[0-9.]+$')
assert_status "partial refund amount in response" "$REFUND_AMOUNT" "20.00"

REFUND_ROW_COUNT=$(psql_query payment_db "SELECT COUNT(*) FROM partial_refund WHERE id = '$ADJUSTMENT_SAGA_ID'")
assert_status "partial_refund row count after first call" "$REFUND_ROW_COUNT" "1"

log "Re-issuing the same partial refund (idempotency by sagaId)..."
curl -sS -o /dev/null -X POST "http://localhost:$(port_for_module payment-service)/payments/partial-refunds" \
    -H 'Content-Type: application/json' \
    -d "{\"sagaId\":\"$ADJUSTMENT_SAGA_ID\",\"amount\":20.00}"

REFUND_ROW_COUNT_AFTER_RETRY=$(psql_query payment_db "SELECT COUNT(*) FROM partial_refund WHERE id = '$ADJUSTMENT_SAGA_ID'")
assert_status "partial_refund row count after idempotent retry" "$REFUND_ROW_COUNT_AFTER_RETRY" "1"

log "PASS: creditStock and issuePartialRefund both completed and issuePartialRefund is idempotent (adjustmentSagaId=$ADJUSTMENT_SAGA_ID)"
