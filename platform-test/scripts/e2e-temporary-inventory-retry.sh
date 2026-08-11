#!/usr/bin/env bash
#
# ==============================================================================================
# Scenario: proposal §17.3 #2 — Temporary inventory error, successful retry (UC-02)
#
# Business context: the order is created, but Inventory Service times out on the saga's first
# reservation attempt (a transient failure, not a rejection — the stock is fine). Unlike
# saga-pattern-poc's Kafka-republished-command retry, here OrderSagaWorkflowImpl's bounded
# RetryOptions makes Temporal itself re-invoke the ReserveStock Activity — same Activity, same
# input, no republished message, no separate attempt counter to inspect. The retried attempt
# succeeds, and the saga completes normally from there.
#
# Trigger used here: sku=SKU-INPUTDATA-2 — InventoryProgressionService.simulateReserveFault throws
# a TemporarySagaException the very first time this SKU is successfully reserved. Because the
# throw happens *after* the reservation and stock debit are already persisted, the retry's
# reserveStock call finds the existing reservation (the same idempotency check that makes a
# genuine retry safe) and returns it directly — no re-debit, no second throw.
#
# Expected result: customer_order CONFIRMED; inventory_reservation CONFIRMED; payment COMPLETED —
# same final state as the happy path, proving the retry, not a coincidence, is what's exercised
# here (see saga-orchestrator-temporal.log for the ReserveStock activity failure + retry).
# ==============================================================================================
#
# Curl-based end-to-end test, without JUnit/Maven driving it. Every run recreates Postgres/Temporal
# from scratch and rebuilds the five module jars, so it is slow (a few minutes) but self-contained
# and reproducible.
#
# Usage: platform-test/scripts/e2e-temporary-inventory-retry.sh

SCENARIO_NAME="temporary-inventory-retry"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

bootstrap_environment

post_order SKU-INPUTDATA-2 2 49.99

log "Waiting for the order to reach CONFIRMED (after one retried reservation attempt)..."
wait_order_status "$BUSINESS_KEY" CONFIRMED

log "Verifying each business service's final state..."
RESERVATION_STATUS=$(psql_query inventory_db "SELECT status FROM inventory_reservation WHERE id = '$SAGA_ID'")
assert_status "inventory_reservation.status" "$RESERVATION_STATUS" "CONFIRMED"

PAYMENT_STATUS=$(psql_query payment_db "SELECT status FROM payment WHERE id = '$SAGA_ID'")
assert_status "payment.status" "$PAYMENT_STATUS" "COMPLETED"

log "Confirming the retry actually happened (Activity failure logged by the Worker)..."
grep -qi "activityType=ReserveStock" "$LOG_DIR/saga-orchestrator-temporal.log" \
    || fail "expected a logged ReserveStock Activity failure (the retried attempt), found none in $LOG_DIR/saga-orchestrator-temporal.log"
log "Retry confirmed."

log "PASS: temporary inventory error was retried and the saga still completed end-to-end (businessKey=$BUSINESS_KEY, sagaId=$SAGA_ID)"
