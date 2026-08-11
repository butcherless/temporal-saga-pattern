#!/usr/bin/env bash
#
# ==============================================================================================
# Scenario: proposal §17.3 #4 — Permanent inventory error before payment (UC-04)
#
# Business context: a customer orders more units than are actually in stock. Inventory Service
# rejects the reservation outright — a permanent failure, not a timeout. Since neither the
# inventory reservation nor the payment was ever created, OrderSagaWorkflowImpl's only
# compensation step is cancelling the order itself; there is nothing to release and nothing to
# refund.
#
# Trigger used here: sku=SKU-001 (100 units seeded by Flyway), quantity=999 — deterministically
# exceeds available stock. StockItem.reserve throws PermanentSagaException synchronously, before
# any inventory_reservation row is ever written. InventoryActivitiesImpl maps the resulting 422
# to a non-retryable Temporal failure, so the Workflow fails on the very first attempt instead of
# exhausting its bounded RetryOptions.
#
# Expected result: customer_order CANCELLED; no inventory_reservation row for this saga at all
# (the reservation attempt failed before it could be persisted); no payment row either (the saga
# never got that far).
# ==============================================================================================
#
# Curl-based end-to-end test, without JUnit/Maven driving it. Every run recreates Postgres/Temporal
# from scratch and rebuilds the five module jars, so it is slow (a few minutes) but self-contained
# and reproducible.
#
# Usage: platform-test/scripts/e2e-permanent-inventory-failure.sh

SCENARIO_NAME="permanent-inventory-failure"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

bootstrap_environment

post_order SKU-001 999 49.99

log "Waiting for the order to reach CANCELLED..."
wait_order_status "$BUSINESS_KEY" CANCELLED

log "Verifying each business service's final state..."
RESERVATION_COUNT=$(psql_query inventory_db "SELECT COUNT(*) FROM inventory_reservation WHERE id = '$SAGA_ID'")
assert_status "inventory_reservation row count" "$RESERVATION_COUNT" "0"

PAYMENT_COUNT=$(psql_query payment_db "SELECT COUNT(*) FROM payment WHERE id = '$SAGA_ID'")
assert_status "payment row count" "$PAYMENT_COUNT" "0"

log "PASS: permanent inventory failure failed fast and correctly cancelled the order (businessKey=$BUSINESS_KEY, sagaId=$SAGA_ID)"
