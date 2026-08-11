#!/usr/bin/env bash
#
# ==============================================================================================
# Scenario: proposal §17.3 #1 — Happy path (UC-01)
#
# Business context: a customer places an order. Every step succeeds on the first try: inventory
# is reserved, payment is charged, the order is confirmed, and the inventory reservation is
# confirmed. No retries, no compensation.
#
# Trigger used here: sku=SKU-001 (100 units seeded by Flyway), quantity=2 (well within stock),
# amount=49.99 (well below both the fake payment gateway's flaky (1000.00) and hard-decline
# (10000.00) thresholds) — nothing here can fail.
#
# Expected result: customer_order CONFIRMED; inventory_reservation CONFIRMED; payment COMPLETED.
# ==============================================================================================
#
# Curl-based end-to-end test, without JUnit/Maven driving it. Complements
# TemporalEndToEndSagaIT.java (JUnit + Testcontainers) with a plain-bash version against the real
# docker-compose stack. Every run recreates Postgres/Temporal from scratch and rebuilds the five
# module jars, so it is slow (a few minutes) but self-contained and reproducible: it never depends
# on state left behind by a previous run or by a manually started `spring-boot:run`.
#
# Usage: platform-test/scripts/e2e-happy-path.sh

SCENARIO_NAME="happy-path"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

bootstrap_environment

post_order SKU-001 2 49.99

log "Waiting for the order to reach CONFIRMED..."
wait_order_status "$BUSINESS_KEY" CONFIRMED

log "Verifying each business service's final state..."
RESERVATION_STATUS=$(psql_query inventory_db "SELECT status FROM inventory_reservation WHERE id = '$SAGA_ID'")
assert_status "inventory_reservation.status" "$RESERVATION_STATUS" "CONFIRMED"

PAYMENT_STATUS=$(psql_query payment_db "SELECT status FROM payment WHERE id = '$SAGA_ID'")
assert_status "payment.status" "$PAYMENT_STATUS" "COMPLETED"

PAYMENT_ATTEMPT=$(psql_query payment_db "SELECT attempt FROM payment WHERE id = '$SAGA_ID'")
assert_status "payment.attempt" "$PAYMENT_ATTEMPT" "1"

log "PASS: happy-path saga completed end-to-end (businessKey=$BUSINESS_KEY, sagaId=$SAGA_ID)"
