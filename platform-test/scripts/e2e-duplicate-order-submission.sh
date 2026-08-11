#!/usr/bin/env bash
#
# ==============================================================================================
# Scenario: proposal §17.3's duplicate-submission scenario (UC-09) — no direct saga-pattern-poc
# equivalent: the custom implementation's e2e-duplicate-event.sh injects a raw duplicate Kafka
# message and relies on inventory-service's Inbox table to dedupe it. There is no messaging layer
# here at all, so the equivalent idempotency boundary is order-service's own POST /orders: two
# HTTP requests for the same businessKey, not two copies of one internal message.
#
# Business context: a client retries a POST /orders call — e.g. after a timed-out response it
# never saw — for a businessKey it already submitted. order-service must not start a second saga
# for it.
#
# Trigger used here: two sequential POST /orders calls with the exact same businessKey/payload.
# OrderCreationHandler.createOrder is idempotent by businessKey: the second call's freshly-minted
# sagaId is discarded once orderProgressionService.createOrder finds the existing row, and
# startSagaIfNewlyCreated skips calling WorkflowClient.start entirely — no second Workflow
# Execution, no WorkflowExecutionAlreadyStarted to even swallow.
#
# Expected result: both POSTs return 202 with the *same* sagaId; exactly one saga runs, and it
# completes normally (customer_order CONFIRMED).
# ==============================================================================================
#
# Curl-based end-to-end test, without JUnit/Maven driving it. Every run recreates Postgres/Temporal
# from scratch and rebuilds the five module jars, so it is slow (a few minutes) but self-contained
# and reproducible.
#
# Usage: platform-test/scripts/e2e-duplicate-order-submission.sh

SCENARIO_NAME="duplicate-order-submission"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

bootstrap_environment

BUSINESS_KEY="E2E-$SCENARIO_NAME-$(date +%Y%m%d%H%M%S)-$RANDOM"

post_order_with_key "$BUSINESS_KEY" SKU-001 2 49.99
FIRST_SAGA_ID=$SAGA_ID

log "Submitting the exact same businessKey a second time..."
post_order_with_key "$BUSINESS_KEY" SKU-001 2 49.99
SECOND_SAGA_ID=$SAGA_ID

assert_status "sagaId returned by the duplicate POST" "$SECOND_SAGA_ID" "$FIRST_SAGA_ID"

log "Waiting for the (single) order to reach CONFIRMED..."
wait_order_status "$BUSINESS_KEY" CONFIRMED

log "Verifying only one saga's worth of state was created..."
ORDER_COUNT=$(psql_query order_db "SELECT COUNT(*) FROM customer_order WHERE business_key = '$BUSINESS_KEY'")
assert_status "customer_order row count" "$ORDER_COUNT" "1"

RESERVATION_STATUS=$(psql_query inventory_db "SELECT status FROM inventory_reservation WHERE id = '$FIRST_SAGA_ID'")
assert_status "inventory_reservation.status" "$RESERVATION_STATUS" "CONFIRMED"

PAYMENT_STATUS=$(psql_query payment_db "SELECT status FROM payment WHERE id = '$FIRST_SAGA_ID'")
assert_status "payment.status" "$PAYMENT_STATUS" "COMPLETED"

log "PASS: duplicate order submission was idempotent — a single saga ran to completion (businessKey=$BUSINESS_KEY, sagaId=$FIRST_SAGA_ID)"
