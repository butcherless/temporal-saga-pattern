#!/usr/bin/env bash
#
# ==============================================================================================
# Scenario: proposal §17.3 #7 — Payment completed, but a later confirmation step fails (UC-08)
#
# Business context: inventory is reserved and the payment is charged successfully, but Order
# Service is then unable to confirm the order. Since the payment already completed, compensating
# now requires more than just releasing inventory and cancelling the order — the payment itself
# must be refunded first. OrderSagaWorkflowImpl's Saga compensates in full: refund the payment,
# release the reservation, cancel the order. The system ends up functionally consistent — payment
# refunded, no held stock, order cancelled.
#
# Trigger used here: amount=250.00 (well under both the fake payment gateway's FLAKY_THRESHOLD and
# HARD_DECLINE_THRESHOLD, so the charge completes cleanly on the first attempt) combined with a
# businessKey containing the CONFIRMFAIL-INPUTDATA-7 marker — OrderProgressionService.confirmOrder's
# simulateConfirmFault always throws a PermanentSagaException for a businessKey containing this
# marker (order-service has no sku/amount field to key a fake gateway off, unlike inventory/
# payment, so businessKey — its only free-form input — is used instead).
#
# Expected result: customer_order CANCELLED (confirmation never persisted); inventory_reservation
# RELEASED, stock restored; payment REFUNDED (was COMPLETED, then refunded as compensation).
# ==============================================================================================
#
# Curl-based end-to-end test, without JUnit/Maven driving it. Every run recreates Postgres/Temporal
# from scratch and rebuilds the five module jars, so it is slow (a few minutes) but self-contained
# and reproducible.
#
# Usage: platform-test/scripts/e2e-payment-completed-confirmation-failure.sh

SCENARIO_NAME="payment-completed-confirmation-failure"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

bootstrap_environment

post_order SKU-001 2 250.00 CONFIRMFAIL-INPUTDATA-7

log "Waiting for the order to reach CANCELLED..."
wait_order_status "$BUSINESS_KEY" CANCELLED

log "Verifying each business service's final state..."
RESERVATION_STATUS=$(psql_query inventory_db "SELECT status FROM inventory_reservation WHERE id = '$SAGA_ID'")
assert_status "inventory_reservation.status" "$RESERVATION_STATUS" "RELEASED"

STOCK_AVAILABLE=$(psql_query inventory_db "SELECT available_quantity FROM stock_item WHERE sku = 'SKU-001'")
assert_status "stock_item.available_quantity (SKU-001)" "$STOCK_AVAILABLE" "100"

PAYMENT_STATUS=$(psql_query payment_db "SELECT status FROM payment WHERE id = '$SAGA_ID'")
assert_status "payment.status" "$PAYMENT_STATUS" "REFUNDED"

log "PASS: order confirmation permanently failed after payment completed, saga fully compensated (businessKey=$BUSINESS_KEY, sagaId=$SAGA_ID)"
