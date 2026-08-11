# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A second implementation of the same Saga Pattern exercise as the sibling repo **`saga-pattern-poc`** — three business services (Order, Inventory, Payment) coordinated through one Saga — this time on **Temporal** instead of a custom Kafka/Outbox/Inbox orchestrator. It is a deliberately separate project, not a branch or module of `saga-pattern-poc`: the two are meant to be compared side by side, accepting some duplicated domain code for now (see `docs/temporal-saga-proposal.md` §1 for why a shared library was deferred rather than built up front).

The design rationale — what maps to what between the two implementations, what's reused, what's new, and the trade-offs — lives in **[`docs/temporal-saga-proposal.md`](./docs/temporal-saga-proposal.md)** (in Spanish, the language it was authored in — like `saga-pattern-poc`'s own `propuesta-saga-pattern-java-springboot-v4.md`, it's a source design doc and isn't translated). Read it before extending this repo; this file only covers what you need to operate it day to day.

**Current state:** all 9 of proposal §17.3's reference scenarios that have a meaningful equivalent under Temporal are implemented and covered end-to-end — happy path, temporary retry (inventory/payment), permanent failure with no compensation needed (inventory/payment), full compensation via `io.temporal.workflow.Saga` (release-fails, refund-fails, full rollback), and idempotent duplicate order submission. Covered by unit tests, `TemporalEndToEndSagaIT` (JUnit + Testcontainers), and `platform-test/scripts/e2e-*.sh` (curl-based, against the real docker-compose stack). The two scenarios with no Temporal equivalent (out-of-order event, DLQ reprocessing — both inherently tied to a message broker this repo doesn't have) are out of scope, not deferred. See [Explicitly deferred](#explicitly-deferred) below for what's still genuinely open.

## Conventions

Same conventions as `saga-pattern-poc` (English-only source/docs except the proposal doc above, TDD, Lombok-only logging, reactive stack wherever a reactive Spring starter exists, `final` method arguments via OpenRewrite, no primitive types in public signatures, OpenAPI 3.1 code-first REST endpoints, append-only Flyway migrations) — see that repo's own `CLAUDE.md` for the full rationale behind each. They're not repeated here.

## Architecture

### What Temporal replaces

There is no `saga-orchestrator` module, no `SagaStatus` state machine, no `saga_instance`/`saga_step` tables, no Kafka, no Outbox/Inbox/DLQ anywhere in this repo. A Temporal **Workflow** (`OrderSagaWorkflowImpl`) *is* the saga: its own method body, executed step by step, durably persisted by the Temporal Server itself rather than by application-owned tables. Each step is a Temporal **Activity** — a plain synchronous HTTP call to a business service, via `WebClient`.

### Module layout

- `saga-common` — shared, Spring-free: `error/` (the `SagaException`/`TemporarySagaException`/`PermanentSagaException` classification, carried over unchanged from `saga-pattern-poc`; each business service's `RestExceptionHandler` maps them to 503/422, which the orchestrator's Activities turn into a retryable vs. non-retryable `ApplicationFailure`) and `workflow/` (the `OrderSagaWorkflow` contract + `OrderSagaInput` + `SagaTaskQueues` — the Temporal analogue of the old `Command`/`Event`/`MessageHeader` envelope, shared between whoever starts the saga and whoever executes it).
- `order-service`, `inventory-service`, `payment-service` — same `domain`/`persistence`/`service` packages as `saga-pattern-poc` (business rules and their idempotency-by-`sagaId`/`businessKey` are unchanged), no `messaging` package at all. `order-service`'s `web` package now starts the Workflow (`WorkflowClient.start(...)`) instead of writing to an outbox; `inventory-service` and `payment-service` each got a brand-new `web` package (they had none before — the custom implementation only ever consumed/produced Kafka) exposing the REST endpoints the orchestrator's Activities call.
- `saga-orchestrator-temporal` — the Temporal Worker: `workflow/OrderSagaWorkflowImpl` (the saga itself) + `activities/` (three `@ActivityInterface`s, one per business service, each implemented as a `WebClient` call) + `config/ActivityWebClientConfig` (one `WebClient` bean per service, disambiguated by bean name/`@Qualifier` — same pattern `saga-pattern-poc` uses for its nested-transaction beans). Owns no database.
- `platform-test` — `TemporalEndToEndSagaIT`: forks all four services as real `java -jar` processes (same reason as `saga-pattern-poc`'s `EndToEndSagaIT` — identically-named classpath resources across service jars rule out an in-process shared-classloader test) against a Testcontainers Postgres (only `order_db`/`inventory_db`/`payment_db` — no `saga_orchestrator_db`) and a Testcontainers `temporalio/temporal:1.8.2` dev server (`server start-dev`, in-memory, no schema of its own — lighter than `docker-compose.yml`'s production-style setup, appropriate for a test that tears everything down afterward).

## Commands

Same Java 25 toolchain, `./mvnw` wrapper, and static-analysis command as `saga-pattern-poc` — see that repo's `CLAUDE.md` for the exact invocations. `./mvnw clean verify` needs Docker for `platform-test`'s IT; there's no other Testcontainers-backed IT yet in this repo (no messaging config to test against a real broker).

### Applying the `final` convention (OpenRewrite, ad hoc)

The `final` conventions above (method arguments, plus local variables and private fields) aren't wired into any `pom.xml` — no `rewrite-maven-plugin` is configured in this repo. Apply them on demand via the `rewrite-maven-plugin`'s `run` goal against `org.openrewrite.recipe:rewrite-static-analysis`, without adding the plugin to any build file:

```bash
mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-static-analysis:LATEST \
  -Drewrite.activeRecipes=org.openrewrite.staticanalysis.FinalizeLocalVariables,org.openrewrite.staticanalysis.FinalizeMethodArguments,org.openrewrite.staticanalysis.FinalizePrivateFields
```

- `org.openrewrite.staticanalysis.FinalizeLocalVariables` — adds `final` to local variables that are never reassigned (skips uninitialized declarations and for-loop control variables).
- `org.openrewrite.staticanalysis.FinalizeMethodArguments` — adds `final` to method parameters.
- `org.openrewrite.staticanalysis.FinalizePrivateFields` — adds `final` to private instance fields that are initialized and never reassigned.

Requires the active JDK to satisfy this repo's Maven Enforcer `RequireJavaVersion` rule (`[25,)`); if the shell's default JDK is older (e.g. via `sdk use java` set to 21), override just for this command: `export JAVA_HOME=~/.sdkman/candidates/java/25.0.3-zulu` (or whatever Java 25 SDKMAN candidate is installed) before running `mvn`. Review the diff before committing — this rewrites working-tree files directly.

### One element per line (custom OpenRewrite recipes, ad hoc)

No published recipe forces every method/constructor parameter, or every record component, onto its own line unconditionally (the stock formatters — `google-java-format`, `palantir-java-format`, OpenRewrite's `WrappingAndBraces` — only wrap when a line exceeds the column limit). `tooling/openrewrite-one-param-per-line/` is a small standalone recipe module hosting two recipes that do this precisely, plus the `OnePerLineSupport` helper they both delegate to (identical whitespace logic, applied to a different padded element list):

- `com.company.tooling.OneParameterPerLine` — for any `J.MethodDeclaration` (covers constructors too) with 2+ parameters, puts every parameter after the first on its own line.
- `com.company.tooling.OneRecordComponentPerLine` — for any record's `J.ClassDeclaration` (`getKind() == Record`) with 2+ components, puts every component after the first on its own line; non-record classes/interfaces/enums are untouched.

Both indent one continuation level (8 spaces) past the declaration; the first element, comments, single/no-element declarations, and everything else in the file are left untouched.

The module is deliberately **not** listed in the root `pom.xml`'s `<modules>` — same "not wired into the build" posture as the `final` recipes above. Install it to the local repo once (or after editing its source), then run either or both recipes against this repo the same way as any published recipe:

```bash
cd tooling/openrewrite-one-param-per-line && mvn install && cd ../..

mvn -U org.openrewrite.maven:rewrite-maven-plugin:run \
  -Drewrite.recipeArtifactCoordinates=com.company.tooling:openrewrite-one-param-per-line:1.0.0-SNAPSHOT \
  -Drewrite.activeRecipes=com.company.tooling.OneParameterPerLine,com.company.tooling.OneRecordComponentPerLine
```

Same Java 25 `JAVA_HOME` requirement as above. An Eclipse-JDT-based alternative was tried first (`formatter-maven-plugin`/Spotless with a custom formatter profile) and rejected: any setting left unset in the profile falls back to Eclipse's own defaults, which reflowed every Javadoc comment and collapsed enum constants onto one line — far beyond the intended scope. These custom recipes avoid that because their visitors only ever touch parameter-list/record-component whitespace.

### Local infra (PostgreSQL + Temporal)

```bash
docker compose up -d      # postgres:5432, temporal:7233 (gRPC), temporal-ui:8088 (http://localhost:8088), pgadmin:5050
docker compose down       # stop; add -v to also drop the postgres/pgadmin volumes
```

- PostgreSQL hosts only the three business databases (`order_db`, `inventory_db`, `payment_db`) — no `saga_orchestrator_db`. Credentials are `saga`/`saga` (`docker-compose.yml`'s `POSTGRES_USER`/`POSTGRES_PASSWORD`), not `postgres`; e.g. `docker exec saga-temporal-postgres psql -U saga -d order_db`.
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

### Starting the full stack (correct order)

Scripted equivalent: `platform-test/scripts/start-all.sh` (steps 1-5 below, then blocks in the foreground — Ctrl-C stops the four service processes; `docker compose` containers are left running).

1. `docker compose up -d` — Postgres + Temporal Server + Temporal UI + pgAdmin (see [Local infra](#local-infra-postgresql--temporal) above). Wait until Postgres accepts connections and the Temporal Web UI (http://localhost:8088) loads before continuing — the business services run Flyway migrations at startup and fail fast without a live Postgres, and `order-service`/`saga-orchestrator-temporal` need a reachable Temporal Server just to connect.
2. Register the `default` namespace — unlike the `temporalio/temporal server start-dev` dev server `platform-test`'s IT uses, `docker-compose.yml`'s production-style `temporalio/server` doesn't create it automatically, and `saga-orchestrator-temporal` fails to start without it (`NOT_FOUND: Namespace default is not found`). Only needed once per fresh Postgres volume:
   ```bash
   docker run --rm --network temporal-saga-pattern_default temporalio/admin-tools:1.31.2 \
     temporal operator namespace create --address saga-temporal:7233 default
   ```
3. `./mvnw install -pl saga-common -DskipTests -q` — every other module depends on it.
4. Start the three business services, one terminal each, in any relative order among themselves (`DB_*` env vars per `saga-pattern-poc`; `order-service` additionally needs `TEMPORAL_TARGET`):
   ```bash
   ./mvnw spring-boot:run -pl order-service
   ./mvnw spring-boot:run -pl inventory-service
   ./mvnw spring-boot:run -pl payment-service
   ```
5. Start the Temporal Worker last, once the business services are already listening — the moment it connects it starts executing any queued Workflow Tasks, which means calling those services' REST endpoints immediately:
   ```bash
   ./mvnw spring-boot:run -pl saga-orchestrator-temporal
   ```
6. Verify: `GET /actuator/health` on each service port (8080-8083), and open the Temporal Web UI (http://localhost:8088) to watch Workflow Executions as they run.

### Stopping the full stack (reverse order)

Scripted equivalent: `platform-test/scripts/stop-all.sh` (steps 1-3 below; uses `docker compose stop`, not `down`, so volumes and the registered namespace survive for the next `start-all.sh`).

1. Stop `saga-orchestrator-temporal` first (Ctrl-C its terminal) — no new saga work gets executed once it's down.
2. Stop the three business services (Ctrl-C each terminal), any relative order among themselves.
3. `docker compose down` — stops Postgres/Temporal/pgAdmin (add `-v` to also drop the Postgres/pgAdmin volumes).

## Explicitly deferred

Not implemented in this repo yet — see `docs/temporal-saga-proposal.md` for the full picture:

- A shared library between this repo and `saga-pattern-poc`, if the duplicated domain code turns out to be worth deduplicating once both implementations are further along.
