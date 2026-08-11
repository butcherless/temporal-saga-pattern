#!/usr/bin/env bash
#
# ==============================================================================================
# Scenario: proposal §17.3 #6 — Permanent payment error with a failed inventory release (UC-06)
#
# Business context: inventory is reserved successfully, but the payment gateway declines the
# charge outright (permanent). OrderSagaWorkflowImpl's Saga starts compensating: release the
# reservation. But releasing it *itself* fails permanently — an unrecoverable compensation
# failure. Unlike saga-pattern-poc (where COMPENSATION_FAILED is a distinct terminal state that
# never reaches order cancellation), here cancelOrder always runs after saga.compensate(),
# regardless of whether every compensation succeeded — so the order still ends CANCELLED, while
# the reservation itself is left RESERVED (release never actually completed).
#
# Triggers used here (composing two independent, already-existing fake-gateway hooks):
#   - amount=15000.00: PaymentProgressionService's HARD_DECLINE_THRESHOLD (>= 10000.00) — the same
#     trigger e2e-permanent-payment-decline.sh uses to start compensation.
#   - sku=SKU-INPUTDATA-6: InventoryProgressionService.simulateReleaseFault always throws a
#     PermanentSagaException when releasing this SKU's reservation (every attempt, not just the
#     first — this failure is meant to be unrecoverable).
#
# Expected result: customer_order CANCELLED; inventory_reservation stays RESERVED (release never
# completes); payment FAILED.
# ==============================================================================================
#
# Curl-based end-to-end test, without JUnit/Maven driving it. Every run recreates Postgres/Temporal
# from scratch and rebuilds the five module jars, so it is slow (a few minutes) but self-contained
# and reproducible.
#
# Usage: platform-test/scripts/e2e-permanent-inventory-release-failure.sh

SCENARIO_NAME="permanent-inventory-release-failure"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

bootstrap_environment

post_order SKU-INPUTDATA-6 2 15000.00

log "Waiting for the order to reach CANCELLED..."
wait_order_status "$BUSINESS_KEY" CANCELLED

log "Verifying each business service's final state..."
RESERVATION_STATUS=$(psql_query inventory_db "SELECT status FROM inventory_reservation WHERE id = '$SAGA_ID'")
assert_status "inventory_reservation.status" "$RESERVATION_STATUS" "RESERVED"

PAYMENT_STATUS=$(psql_query payment_db "SELECT status FROM payment WHERE id = '$SAGA_ID'")
assert_status "payment.status" "$PAYMENT_STATUS" "FAILED"

log "PASS: inventory release permanently failed during compensation, order still correctly cancelled, reservation left RESERVED (businessKey=$BUSINESS_KEY, sagaId=$SAGA_ID)"
