#!/usr/bin/env bash
#
# ==============================================================================================
# Scenario: proposal §17.3 #3 — Temporary payment error, successful retry (UC-03)
#
# Business context: inventory reserves cleanly, but the payment gateway times out on the saga's
# first charge attempt (a transient failure). OrderSagaWorkflowImpl's bounded RetryOptions makes
# Temporal re-invoke the RequestPayment Activity; the retried attempt succeeds.
#
# Trigger used here: amount=2000.00 — at/above PaymentProgressionService.FLAKY_THRESHOLD
# (1000.00) but below HARD_DECLINE_THRESHOLD (10000.00), and this is the payment's first attempt
# (attempt=1), so PaymentProgressionService.evaluate throws a TemporarySagaException once. The
# retry's requestPayment call increments the persisted attempt counter and re-evaluates — this
# time attempt != 1, so it completes.
#
# Expected result: customer_order CONFIRMED; inventory_reservation CONFIRMED; payment COMPLETED
# with attempt=2 (proof it took a genuine retry, not a first-try success).
# ==============================================================================================
#
# Curl-based end-to-end test, without JUnit/Maven driving it. Every run recreates Postgres/Temporal
# from scratch and rebuilds the five module jars, so it is slow (a few minutes) but self-contained
# and reproducible.
#
# Usage: platform-test/scripts/e2e-temporary-payment-retry.sh

SCENARIO_NAME="temporary-payment-retry"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

bootstrap_environment

post_order SKU-001 2 2000.00

log "Waiting for the order to reach CONFIRMED (after one retried payment attempt)..."
wait_order_status "$BUSINESS_KEY" CONFIRMED

log "Verifying each business service's final state..."
RESERVATION_STATUS=$(psql_query inventory_db "SELECT status FROM inventory_reservation WHERE id = '$SAGA_ID'")
assert_status "inventory_reservation.status" "$RESERVATION_STATUS" "CONFIRMED"

PAYMENT_STATUS=$(psql_query payment_db "SELECT status FROM payment WHERE id = '$SAGA_ID'")
assert_status "payment.status" "$PAYMENT_STATUS" "COMPLETED"

PAYMENT_ATTEMPT=$(psql_query payment_db "SELECT attempt FROM payment WHERE id = '$SAGA_ID'")
assert_status "payment.attempt" "$PAYMENT_ATTEMPT" "2"

log "PASS: temporary payment error was retried and the saga still completed end-to-end (businessKey=$BUSINESS_KEY, sagaId=$SAGA_ID)"
