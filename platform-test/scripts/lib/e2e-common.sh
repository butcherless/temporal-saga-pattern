#!/usr/bin/env bash
#
# Shared functions for the curl-based e2e scenario scripts in ../*.sh. Not runnable on its own —
# each scenario script sets SCENARIO_NAME, then sources this file, then calls bootstrap_environment
# and post_order. Portable to bash 3.2 (macOS's default /bin/bash): parallel indexed arrays, no
# `declare -A`. Mirrors saga-pattern-poc/platform-test/scripts/lib/e2e-common.sh's shape; see the
# per-function comments below for where and why this repo's version differs.
#
# Requirements: docker (with the "compose" plugin), curl, java 25 on PATH, and this project's
# ./mvnw.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

: "${SCENARIO_NAME:?SCENARIO_NAME must be set before sourcing e2e-common.sh}"
readonly LOG_DIR="$REPO_ROOT/platform-test/target/e2e-logs/$SCENARIO_NAME"

readonly POSTGRES_CONTAINER="saga-temporal-postgres"
readonly TEMPORAL_CONTAINER="saga-temporal"
readonly TEMPORAL_NETWORK="temporal-saga-pattern_default"
readonly TEMPORAL_ADMIN_IMAGE="temporalio/admin-tools:1.31.2"

# Parallel arrays (not an associative array: the default /bin/bash on macOS is 3.2, which
# predates `declare -A`) — SERVICE_PORTS[i] is the port for SERVICES[i].
readonly SERVICES=(order-service inventory-service payment-service saga-orchestrator-temporal)
readonly SERVICE_PORTS=(8081 8082 8083 8080)
# Unlike saga-pattern-poc (pure Kafka, no start-order dependency), the Temporal Worker starts
# executing any queued Workflow Task the moment it connects — i.e. calling the business services'
# REST endpoints immediately — so it must start last (see CLAUDE.md "Starting the full stack").
readonly START_ORDER=(order-service inventory-service payment-service saga-orchestrator-temporal)
readonly HEALTH_TIMEOUT_S=60
readonly SAGA_TIMEOUT_S=60

PIDS=()

log() { echo "[e2e] $*"; }
fail() { echo "[e2e] FAIL: $*" >&2; exit 1; }

cleanup() {
    local exit_code=$?
    for pid in "${PIDS[@]:-}"; do
        kill "$pid" >/dev/null 2>&1 || true
    done
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

port_for_module() {
    local target=$1 i
    for i in "${!SERVICES[@]}"; do
        if [[ "${SERVICES[$i]}" == "$target" ]]; then
            echo "${SERVICE_PORTS[$i]}"
            return 0
        fi
    done
    fail "unknown module: $target"
}

wait_container_healthy() {
    local container=$1 waited=0
    while true; do
        local status
        status=$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || echo "missing")
        [[ "$status" == "healthy" ]] && return 0
        waited=$((waited + 2))
        [[ $waited -ge $HEALTH_TIMEOUT_S ]] && fail "container $container did not become healthy within ${HEALTH_TIMEOUT_S}s (last status: $status)"
        sleep 2
    done
}

# docker-compose.yml's `temporal` service has no healthcheck (unlike postgres), so readiness is
# proven the same way CLAUDE.md's manual procedure discovered it must be: by successfully talking
# gRPC to it. Registering the "default" namespace this way both waits for Temporal to be ready
# *and* performs the one-time setup saga-orchestrator-temporal needs to start at all (production-
# style temporalio/server, unlike the dev-mode `server start-dev` platform-test's IT uses, never
# creates it automatically).
wait_temporal_ready_and_register_namespace() {
    local waited=0 output
    while true; do
        if output=$(docker run --rm --network "$TEMPORAL_NETWORK" "$TEMPORAL_ADMIN_IMAGE" \
                temporal operator namespace create --address "$TEMPORAL_CONTAINER:7233" default 2>&1); then
            log "Registered the \"default\" Temporal namespace."
            return 0
        fi
        if echo "$output" | grep -qi "already exists"; then
            log "Temporal \"default\" namespace already exists."
            return 0
        fi
        waited=$((waited + 2))
        [[ $waited -ge $HEALTH_TIMEOUT_S ]] && fail "Temporal Server did not become ready within ${HEALTH_TIMEOUT_S}s (last error: $output)"
        sleep 2
    done
}

free_port() {
    local port=$1
    local pid
    pid=$(lsof -ti "tcp:$port" 2>/dev/null || true)
    if [[ -n "$pid" ]]; then
        log "Killing stale process on port $port (pid $pid)"
        kill -9 $pid 2>/dev/null || true
    fi
}

start_service() {
    local module=$1 port jar
    port=$(port_for_module "$module")
    jar="$REPO_ROOT/$module/target/$module-$VERSION-exec.jar"
    [[ -f "$jar" ]] || fail "jar not found: $jar (build step did not produce it)"
    java -jar "$jar" --spring.profiles.active=local >"$LOG_DIR/$module.log" 2>&1 &
    PIDS+=($!)
    log "Started $module (pid $!, port $port), logging to $LOG_DIR/$module.log"
}

wait_service_healthy() {
    local module=$1 port waited=0
    port=$(port_for_module "$module")
    while true; do
        if curl -sf "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"UP"'; then
            return 0
        fi
        waited=$((waited + 1))
        [[ $waited -ge $HEALTH_TIMEOUT_S ]] && fail "$module on port $port did not become healthy within ${HEALTH_TIMEOUT_S}s (see $LOG_DIR/$module.log)"
        sleep 1
    done
}

psql_query() {
    local db=$1 sql=$2
    docker exec -i "$POSTGRES_CONTAINER" psql -U saga -d "$db" -tAc "$sql" | tr -d '[:space:]'
}

assert_status() {
    local label=$1 actual=$2 expected=$3
    [[ "$actual" == "$expected" ]] || fail "$label = '$actual', expected '$expected'"
    log "$label = $actual"
}

# Replaces saga-pattern-poc's wait_saga_status: there is no saga_instance table here (no
# application-owned state machine — Temporal's own server tracks the Workflow Execution). The
# terminal signal available over plain psql is customer_order.status itself: OrderSagaWorkflowImpl
# always ends by confirming or cancelling the order, so polling it *is* polling the saga's outcome.
wait_order_status() {
    local business_key=$1 expected=$2 waited=0 last=""
    while true; do
        last=$(psql_query order_db "SELECT status FROM customer_order WHERE business_key = '$business_key'")
        [[ "$last" == "$expected" ]] && return 0
        waited=$((waited + 1))
        [[ $waited -ge $SAGA_TIMEOUT_S ]] && fail "order for businessKey=$business_key did not reach $expected within ${SAGA_TIMEOUT_S}s (last seen: ${last:-<no row>})"
        sleep 1
    done
}

# Recreates Postgres+Temporal from scratch, rebuilds the five module jars (saga-common plus the
# four services), starts the three business services then the Temporal Worker last, and waits for
# all four to report healthy. Identical across every scenario script.
bootstrap_environment() {
    mkdir -p "$LOG_DIR"

    require_command docker
    require_command curl
    require_command java
    require_command lsof

    log "Recreating Postgres + Temporal from a clean slate..."
    docker compose down -v
    docker compose up -d postgres temporal temporal-schema-setup temporal-ui
    wait_container_healthy "$POSTGRES_CONTAINER"
    wait_temporal_ready_and_register_namespace
    log "Postgres + Temporal are ready."

    local module
    for module in "${SERVICES[@]}"; do
        free_port "$(port_for_module "$module")"
    done

    log "Building executable jars (unit tests skipped)..."
    ./mvnw clean verify -DskipTests -pl saga-common,order-service,inventory-service,payment-service,saga-orchestrator-temporal -am -q -B

    VERSION=$(grep -m1 -oE '<version>[^<]+</version>' pom.xml | sed -E 's#</?version>##g')
    log "Resolved project version: $VERSION"

    for module in "${START_ORDER[@]}"; do
        start_service "$module"
        wait_service_healthy "$module"
    done
    log "All four services are healthy (business services first, Temporal Worker last)."
}

# POST /orders with the given sku/quantity/amount and a freshly generated businessKey (so re-runs
# never collide with a previous run's idempotency state). An optional 4th argument is appended to
# the generated businessKey — used by scenarios that trigger a fault via a businessKey marker
# (order-service has no sku/amount field to key off, unlike inventory/payment, so businessKey is
# its "input data" instead). Sets SAGA_ID and BUSINESS_KEY on success; fails if the response isn't
# 202 (every scenario succeeds at this synchronous step — the temporary/permanent failures this
# suite drives all happen later, asynchronously, downstream).
post_order() {
    local sku=$1 quantity=$2 amount=$3 business_key_suffix=${4:-}
    BUSINESS_KEY="E2E-$SCENARIO_NAME-$(date +%Y%m%d%H%M%S)-$RANDOM${business_key_suffix:+-$business_key_suffix}"
    post_order_with_key "$BUSINESS_KEY" "$sku" "$quantity" "$amount"
}

# Same as post_order, but with a caller-supplied businessKey instead of a freshly generated one —
# used by e2e-duplicate-order-submission.sh to POST the same businessKey twice. Sets SAGA_ID and
# BUSINESS_KEY on success.
post_order_with_key() {
    local business_key=$1 sku=$2 quantity=$3 amount=$4
    BUSINESS_KEY="$business_key"
    log "POST /orders (sku=$sku, quantity=$quantity, amount=$amount, businessKey=$BUSINESS_KEY)..."
    local response http_code body
    response=$(curl -sS -w '\n%{http_code}' -X POST "http://localhost:$(port_for_module order-service)/orders" \
        -H 'Content-Type: application/json' \
        -d "{\"sku\":\"$sku\",\"quantity\":$quantity,\"amount\":$amount,\"businessKey\":\"$BUSINESS_KEY\"}")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    [[ "$http_code" == "202" ]] || fail "POST /orders returned $http_code, expected 202 (body: $body)"

    SAGA_ID=$(echo "$body" | grep -oE '"sagaId"[[:space:]]*:[[:space:]]*"[^"]+"' | grep -oE '"[0-9a-fA-F-]{36}"' | tr -d '"')
    [[ -n "$SAGA_ID" ]] || fail "could not extract sagaId from response body: $body"
    log "sagaId=$SAGA_ID"
}
