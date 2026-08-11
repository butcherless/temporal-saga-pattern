#!/usr/bin/env bash
#
# ==============================================================================================
# Scenario: proposal §17.3 #5 — Permanent payment error with inventory release (UC-05)
#
# Business context: inventory is reserved successfully, but the payment gateway declines the
# charge outright — a permanent failure, not a timeout. Since inventory was already reserved,
# OrderSagaWorkflowImpl's Saga compensates: release the reservation, then cancel the order. The
# system ends up functionally consistent — no charge, no held stock, order cancelled.
#
# Trigger used here: amount=15000.00 — at/above the fake payment gateway's HARD_DECLINE_THRESHOLD
# (10000.00), so PaymentProgressionService.evaluate throws a PermanentSagaException immediately,
# with no retry.
#
# Expected result: customer_order CANCELLED; inventory_reservation RELEASED (reserved, then
# released as compensation); payment FAILED (never COMPLETED).
# ==============================================================================================
#
# Curl-based end-to-end test, without JUnit/Maven driving it. Every run recreates Postgres/Temporal
# from scratch and rebuilds the five module jars, so it is slow (a few minutes) but self-contained
# and reproducible.
#
# Usage: platform-test/scripts/e2e-permanent-payment-decline.sh

SCENARIO_NAME="permanent-payment-decline"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

bootstrap_environment

post_order SKU-001 2 15000.00

log "Waiting for the order to reach CANCELLED..."
wait_order_status "$BUSINESS_KEY" CANCELLED

log "Verifying each business service's final state..."
RESERVATION_STATUS=$(psql_query inventory_db "SELECT status FROM inventory_reservation WHERE id = '$SAGA_ID'")
assert_status "inventory_reservation.status" "$RESERVATION_STATUS" "RELEASED"

PAYMENT_STATUS=$(psql_query payment_db "SELECT status FROM payment WHERE id = '$SAGA_ID'")
assert_status "payment.status" "$PAYMENT_STATUS" "FAILED"

log "PASS: permanent payment decline correctly compensated with inventory release + order cancellation (businessKey=$BUSINESS_KEY, sagaId=$SAGA_ID)"
