#!/usr/bin/env bash
# Manual curl equivalent of platform-test's TemporalEndToEndSagaIT happy path:
# POST /orders on order-service starts the OrderSagaWorkflow, which reserves
# stock, charges payment, and confirms the order — driving customer_order.status
# to CONFIRMED and the Workflow Execution to COMPLETED.
#
# Requires: docker compose up -d, plus saga-orchestrator-temporal, order-service,
# inventory-service and payment-service already running (see CLAUDE.md).
set -euo pipefail

command -v jq >/dev/null || { echo "jq is required (brew install jq)" >&2; exit 1; }

ORDER_SERVICE_URL="http://localhost:8081"
TEMPORAL_UI_URL="http://localhost:8088"

BUSINESS_KEY="ORDER-$(date +%Y)-CURL-$(uuidgen)"

echo "== POST /orders (businessKey=${BUSINESS_KEY}) =="
RESPONSE=$(curl -sS -w '\n%{http_code}' -X POST "${ORDER_SERVICE_URL}/orders" \
  -H 'Content-Type: application/json' \
  -d "{\"sku\":\"SKU-001\",\"quantity\":2,\"amount\":49.99,\"businessKey\":\"${BUSINESS_KEY}\"}")

STATUS=$(echo "${RESPONSE}" | tail -n 1)
BODY=$(echo "${RESPONSE}" | sed '$d')

echo "HTTP ${STATUS}"
echo "${BODY}" | jq .

if [ "${STATUS}" != "202" ]; then
  echo "Expected 202 Accepted, got ${STATUS}" >&2
  exit 1
fi

SAGA_ID=$(echo "${BODY}" | jq -r '.sagaId')
echo
echo "sagaId=${SAGA_ID}"
echo "Workflow Id (same as businessKey): ${BUSINESS_KEY}"
echo
echo "Track it in the Temporal Web UI: ${TEMPORAL_UI_URL}/namespaces/default/workflows/${BUSINESS_KEY}"
echo "(The Workflow closes as COMPLETED once reserveStock -> charge -> confirmOrder -> confirmReservation all succeed.)"
