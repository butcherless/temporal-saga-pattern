# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A second implementation of the same Saga Pattern exercise as the sibling repo **`saga-pattern-poc`** — three business services (Order, Inventory, Payment) coordinated through one Saga — this time on **Temporal** instead of a custom Kafka/Outbox/Inbox orchestrator. It is a deliberately separate project, not a branch or module of `saga-pattern-poc`: the two are meant to be compared side by side, accepting some duplicated domain code for now (see `docs/temporal-saga-proposal.md` §1 for why a shared library was deferred rather than built up front).

The design rationale — what maps to what between the two implementations, what's reused, what's new, and the trade-offs — lives in **[`docs/temporal-saga-proposal.md`](./docs/temporal-saga-proposal.md)** (in Spanish, the language it was authored in — like `saga-pattern-poc`'s own `propuesta-saga-pattern-java-springboot-v4.md`, it's a source design doc and isn't translated). Read it before extending this repo; this file only covers what you need to operate it day to day.

**Current state:** scaffold + happy path only (reserve stock → charge payment → confirm the order → confirm the reservation), with an end-to-end IT proving `POST /orders` drives the saga's Workflow Execution to `COMPLETED`. Retry/backoff, compensation, and the rest of the reference scenarios are explicitly **not** implemented yet — see [Explicitly deferred](#explicitly-deferred) below.

## Conventions

Same conventions as `saga-pattern-poc` (English-only source/docs except the proposal doc above, TDD, Lombok-only logging, reactive stack wherever a reactive Spring starter exists, `final` method arguments via OpenRewrite, no primitive types in public signatures, OpenAPI 3.1 code-first REST endpoints, append-only Flyway migrations) — see that repo's own `CLAUDE.md` for the full rationale behind each. They're not repeated here.

## Architecture

### What Temporal replaces

There is no `saga-orchestrator` module, no `SagaStatus` state machine, no `saga_instance`/`saga_step` tables, no Kafka, no Outbox/Inbox/DLQ anywhere in this repo. A Temporal **Workflow** (`OrderSagaWorkflowImpl`) *is* the saga: its own method body, executed step by step, durably persisted by the Temporal Server itself rather than by application-owned tables. Each step is a Temporal **Activity** — a plain synchronous HTTP call to a business service, via `WebClient`.

### Module layout

- `saga-common` — shared, Spring-free: `error/` (the `SagaException`/`TemporarySagaException`/`PermanentSagaException` classification, carried over unchanged from `saga-pattern-poc`, not yet wired into any retry logic in this repo) and `workflow/` (the `OrderSagaWorkflow` contract + `OrderSagaInput` + `SagaTaskQueues` — the Temporal analogue of the old `Command`/`Event`/`MessageHeader` envelope, shared between whoever starts the saga and whoever executes it).
- `order-service`, `inventory-service`, `payment-service` — same `domain`/`persistence`/`service` packages as `saga-pattern-poc` (business rules and their idempotency-by-`sagaId`/`businessKey` are unchanged), no `messaging` package at all. `order-service`'s `web` package now starts the Workflow (`WorkflowClient.start(...)`) instead of writing to an outbox; `inventory-service` and `payment-service` each got a brand-new `web` package (they had none before — the custom implementation only ever consumed/produced Kafka) exposing the REST endpoints the orchestrator's Activities call.
- `saga-orchestrator-temporal` — the Temporal Worker: `workflow/OrderSagaWorkflowImpl` (the saga itself) + `activities/` (three `@ActivityInterface`s, one per business service, each implemented as a `WebClient` call) + `config/ActivityWebClientConfig` (one `WebClient` bean per service, disambiguated by bean name/`@Qualifier` — same pattern `saga-pattern-poc` uses for its nested-transaction beans). Owns no database.
- `platform-test` — `TemporalEndToEndSagaIT`: forks all four services as real `java -jar` processes (same reason as `saga-pattern-poc`'s `EndToEndSagaIT` — identically-named classpath resources across service jars rule out an in-process shared-classloader test) against a Testcontainers Postgres (only `order_db`/`inventory_db`/`payment_db` — no `saga_orchestrator_db`) and a Testcontainers `temporalio/temporal:1.8.2` dev server (`server start-dev`, in-memory, no schema of its own — lighter than `docker-compose.yml`'s production-style setup, appropriate for a test that tears everything down afterward).

## Commands

Same Java 25 toolchain, `./mvnw` wrapper, and static-analysis command as `saga-pattern-poc` — see that repo's `CLAUDE.md` for the exact invocations. `./mvnw clean verify` needs Docker for `platform-test`'s IT; there's no other Testcontainers-backed IT yet in this repo (no messaging config to test against a real broker).

### Local infra (PostgreSQL + Temporal)

```bash
docker compose up -d      # postgres:5432, temporal:7233 (gRPC), temporal-ui:8088 (http://localhost:8088), pgadmin:5050
docker compose down       # stop; add -v to also drop the postgres/pgadmin volumes
```

- PostgreSQL hosts only the three business databases (`order_db`, `inventory_db`, `payment_db`) — no `saga_orchestrator_db`.
- Temporal runs as `temporalio/server` + a one-shot `temporalio/admin-tools` schema-setup service against that same Postgres container, following the current officially-documented self-hosted pattern (`temporalio/samples-server`'s `compose/docker-compose-postgres.yml`) — `temporalio/auto-setup`, the older all-in-one image, is deprecated.
- **Temporal Web UI (http://localhost:8088)** is the equivalent of `saga-pattern-poc`'s Kafka UI for this repo: open a Workflow Execution to see its full event history (every Activity call, its input/output, retries) — no separate `saga_step` table to query.

### Running a service locally

Same pattern and port table as `saga-pattern-poc` (`saga-orchestrator-temporal` takes the `8080` slot `saga-orchestrator` used to occupy):

| Service | Port |
|---|---|
| `saga-orchestrator-temporal` | 8080 |
| `order-service` | 8081 |
| `inventory-service` | 8082 |
| `payment-service` | 8083 |

```bash
./mvnw install -pl saga-common -DskipTests -q
./mvnw spring-boot:run -pl <module>
```

`saga-orchestrator-temporal` and `order-service` both need `TEMPORAL_TARGET` set (defaults to `localhost:7233`, matching `docker compose up -d` above) to reach the Temporal Server; `order-service`/`inventory-service`/`payment-service` need the same `DB_*` env vars as in `saga-pattern-poc`.

## Explicitly deferred

Not implemented in this repo yet — see `docs/temporal-saga-proposal.md` for the full picture:

- Retry/backoff (`RetryPolicy` on `ActivityOptions`) and TEMPORARY→PERMANENT classification via a non-retryable `ApplicationFailure`.
- Compensation via the SDK's `io.temporal.workflow.Saga` helper (the reference scenarios where a permanent failure rolls back prior steps).
- `releaseStock`/`refundPayment` endpoints and their Activities.
- Scenario shell scripts equivalent to `saga-pattern-poc/platform-test/scripts/e2e-*.sh`.
- A shared library between this repo and `saga-pattern-poc`, if the duplicated domain code turns out to be worth deduplicating once both implementations are further along.
