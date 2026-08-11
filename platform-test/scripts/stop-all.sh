#!/usr/bin/env bash
#
# ==============================================================================================
# Stops everything start-all.sh started, in the reverse of its own startup order (Temporal Worker
# first, then the business services, then the shared infra) — mirrors CLAUDE.md's "Stopping the
# full stack" procedure. A safe complement for when Ctrl-C on start-all.sh isn't available (e.g.
# it was backgrounded, or its terminal was closed) or you'd rather stop things from a separate
# shell without disturbing the one still tailing its output.
#
# Uses `docker compose stop`, not `down`, so container state and volumes (Postgres data, pgAdmin's
# registered server, the Temporal "default" namespace already registered) survive — re-run
# start-all.sh afterward and they come back without needing to re-register the namespace. If you
# actually want a clean-slate reset instead, `docker compose down -v` does that (same as what
# bootstrap_environment itself runs before every e2e-*.sh/start-all.sh).
#
# Usage: platform-test/scripts/stop-all.sh
# ==============================================================================================

SCENARIO_NAME="stop-all"
source "$(dirname "${BASH_SOURCE[0]}")/lib/e2e-common.sh"

# Reverse of START_ORDER (order-service, inventory-service, payment-service,
# saga-orchestrator-temporal) — see lib/e2e-common.sh.
STOP_ORDER=(saga-orchestrator-temporal payment-service inventory-service order-service)

log "Stopping services (Temporal Worker first, business services after)..."
for module in "${STOP_ORDER[@]}"; do
    port=$(port_for_module "$module")
    pid=$(lsof -ti "tcp:$port" 2>/dev/null || true)
    if [[ -n "$pid" ]]; then
        log "Stopping $module (pid $pid, port $port)..."
        kill "$pid" 2>/dev/null || true
    else
        log "$module was not running (port $port free)."
    fi
done

log "Stopping docker compose containers (postgres/temporal/temporal-ui/pgadmin)..."
docker compose stop postgres temporal temporal-ui pgadmin

log "All stopped. Container state and volumes were kept — 'docker compose down -v' instead for a full reset."
