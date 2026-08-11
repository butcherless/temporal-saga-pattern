#!/usr/bin/env bash
#
# ==============================================================================================
# Scenario: proposal §17.3 #8 — Refund itself fails permanently (UC-07)
#
# Business context: same starting point as e2e-payment-completed-confirmation-failure.sh —
# inventory is reserved and the payment is charged successfully, but Order Service cannot confirm
# the order, so OrderSagaWorkflowImpl's Saga starts compensating by refunding the payment. This
# time the refund *itself* fails permanently. Because Saga.Options.setContinueWithError(true) is
# set, the release compensation registered before the refund still runs afterward — the
# reservation ends up RELEASED even though the refund failed. cancelOrder still runs regardless
# (unlike saga-pattern-poc, where a failed compensation leaves the order at PENDING).
#
# Triggers used here (composing two independent, already-existing fake-gateway hooks):
#   - businessKey containing CONFIRMFAIL-INPUTDATA-7: OrderProgressionService.confirmOrder's
#     simulateConfirmFault always throws a PermanentSagaException for this marker — the same
#     trigger e2e-payment-completed-confirmation-failure.sh uses to start compensation.
#   - amount=750.00: PaymentProgressionService.simulateRefundFault always throws a
#     PermanentSagaException when refunding a payment for exactly this amount (still well under
#     the charge-side FLAKY_THRESHOLD/HARD_DECLINE_THRESHOLD, so the original charge completes
#     cleanly — only the later refund attempt fails).
#
# Expected result: customer_order CANCELLED; inventory_reservation RELEASED (release still ran);
# payment stays COMPLETED (refund never persisted).
# ==============================================================================================
#
# Curl-based end-to-end test, without JUnit/Maven driving it. Every run recreates Postgres/Temporal
# from scratch and rebuilds the five module jars, so it is slow (a few minutes) but self-contained
# and reproducible.
#
# Usage: platform-test/scripts/e2e-permanent-refund-failure.sh

SCENARIO_NAME="permanent-refund-failure"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

bootstrap_environment

post_order SKU-001 2 750.00 CONFIRMFAIL-INPUTDATA-7

log "Waiting for the order to reach CANCELLED..."
wait_order_status "$BUSINESS_KEY" CANCELLED

log "Verifying each business service's final state..."
RESERVATION_STATUS=$(psql_query inventory_db "SELECT status FROM inventory_reservation WHERE id = '$SAGA_ID'")
assert_status "inventory_reservation.status" "$RESERVATION_STATUS" "RELEASED"

PAYMENT_STATUS=$(psql_query payment_db "SELECT status FROM payment WHERE id = '$SAGA_ID'")
assert_status "payment.status" "$PAYMENT_STATUS" "COMPLETED"

log "PASS: refund permanently failed during compensation, release still ran and order was still cancelled (businessKey=$BUSINESS_KEY, sagaId=$SAGA_ID)"
