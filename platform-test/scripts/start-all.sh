#!/usr/bin/env bash
#
# ==============================================================================================
# Starts the full local environment (Postgres + Temporal + all four services) and leaves it
# running in the foreground, for manual exploration — curl, the Temporal Web UI, pgAdmin, a
# debugger attached to one of the jars, etc. Unlike the e2e-*.sh scenario scripts (which bootstrap
# the same environment but then drive one specific business scenario and tear everything down when
# they exit), this script does nothing but bootstrap and wait: it reuses bootstrap_environment()
# from lib/e2e-common.sh unchanged, so it recreates Postgres/Temporal from scratch, rebuilds the
# five module jars, registers the Temporal "default" namespace, and starts the three business
# services then the Worker last — exactly the way every e2e scenario does, and exactly what
# CLAUDE.md's "Starting the full stack" section documents by hand.
#
# Stop with Ctrl-C: the shared lib's own `trap cleanup EXIT INT TERM` (lib/e2e-common.sh) kills all
# four service processes for you. Postgres/Temporal/Temporal UI/pgAdmin themselves keep running via
# `docker compose` — stop those separately with `docker compose down` (add -v to also drop data),
# or run stop-all.sh from another shell instead.
#
# Usage: platform-test/scripts/start-all.sh
# ==============================================================================================

SCENARIO_NAME="start-all"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

bootstrap_environment

docker compose up -d pgadmin >/dev/null

log ""
log "All services are up. Logs: $LOG_DIR/<module>.log"
log ""
log "  order-service              http://localhost:8081/actuator/health   (POST /orders here)"
log "  inventory-service          http://localhost:8082/actuator/health"
log "  payment-service            http://localhost:8083/actuator/health"
log "  saga-orchestrator-temporal http://localhost:8080/actuator/health"
log "  Temporal Web UI            http://localhost:8088"
log "  pgAdmin                    http://localhost:5050  (admin@example.com / admin)"
log ""
log "Press Ctrl-C to stop the four services (Postgres/Temporal/Temporal UI/pgAdmin keep running;"
log "stop those with 'docker compose down', or run stop-all.sh from another shell)."

wait
