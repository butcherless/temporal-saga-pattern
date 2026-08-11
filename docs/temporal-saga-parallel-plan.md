# `temporal-saga-parallel-plan`: a second comparison axis — dependent vs. parallel service orchestration

> Planning/requirements doc for a **future sibling repo**, `temporal-saga-pattern-parallel`. Nothing here is implemented yet: this doc only fixes the domain model, the Workflow contract, the REST controller layer, and the Maven project structure at a requirements level, so the next phase can scaffold the actual repo against something concrete. Status: **draft, open for refinement** — see §8.

## 1. Why this comparison, and what it holds constant

`temporal-saga-pattern` (this repo) and `saga-pattern-poc` compare two different **orchestration engines** (Temporal vs. a custom Kafka/Outbox/Inbox implementation) over the *same* dependent business flow. This doc proposes a third repo that instead holds the orchestration engine constant — same Temporal approach, same conventions, same module-layout style — and varies only the **shape of the business dependency graph**: a chain of steps that each need the previous step's result, vs. three independent bookings that don't depend on each other at all.

**Domain:** a *holiday package* — one saga coordinating three independent reservation legs: airline, hotel, and car. Unlike Order→Inventory→Payment, none of these three bookings needs another one's output to proceed. That's deliberate: it's the cleanest way to contrast a dependent chain against a genuinely parallel fan-out.

### What differs

| Aspect | `temporal-saga-pattern` (existing) | `temporal-saga-pattern-parallel` (this doc) |
|---|---|---|
| Business relationship | Dependent: charge payment needs stock reserved; confirm order/reservation need payment's outcome | Independent: airline/hotel/car bookings share no data dependency |
| Workflow code shape | Sequential activity-stub calls, each blocking the Workflow Task until it resolves before the next command is issued | `Async.procedure(...)` fan-out issuing all 3 Activity commands within the same Workflow Task, then `Promise.allOf(...).get()` fan-in |
| Failure blast radius | A failure at step N means steps N+1..4 are never attempted | A failure in one leg doesn't prevent the other two from having already been dispatched — by the time the Workflow observes the failure, 0, 1, or 2 of the other legs may already have succeeded |
| Compensation shape (future) | A natural LIFO stack via `io.temporal.workflow.Saga`, since steps are already ordered | Not a clean LIFO stack — the legs needing undoing are whichever concurrent Activities actually succeeded, in no particular order. A materially different design problem, not just "the same thing, not built yet" — see §6 |
| Task queue / Worker | Single Task Queue, single Worker | Same pattern, renamed |

### What stays identical

- Maven multi-module layout style: `saga-common` (Spring-free) + N business services (`domain`/`persistence`/`service`/`web`) + `saga-orchestrator-temporal` (Worker) + `platform-test`.
- Tech stack: Spring Boot reactive stack, WebFlux + `WebClient` for Activities, R2DBC + Flyway per service, OpenAPI 3.1 code-first REST, Temporal Java SDK, `temporal-spring-boot-starter`.
- Conventions from this repo's `CLAUDE.md`: English-only source/docs, TDD, Lombok-only logging, `final` method arguments via OpenRewrite, no primitive types in public signatures, append-only Flyway migrations.
- Idempotency-by-`sagaId`/`businessKey` discipline, per service.
- `docker-compose.yml` infra pattern (Postgres + `temporalio/server` + one-shot `temporalio/admin-tools` schema setup + Temporal Web UI at 8088), reused near-verbatim with renamed databases.
- `platform-test`'s fork-real-jars-against-Testcontainers IT strategy (`TemporalEndToEndSagaIT` → an analogous `HolidayPackageParallelEndToEndSagaIT`).
- The precedent already stated in this repo's own `CLAUDE.md` (§"Explicitly deferred," last bullet): duplicated domain/`saga-common` code between sibling repos is accepted for now rather than sharing a jar. The same call applies between `temporal-saga-pattern` and `temporal-saga-pattern-parallel`.

## 2. Domain model

A new `saga-common` `workflow` package, analogous to `OrderSagaInput` but with per-service nested records — justified because, unlike `sku`/`quantity`/`amount` (flat, order-level scalars), airline/hotel/car have genuinely disjoint field sets; a flat record here would need field-name prefixes and obscure which fields belong to which downstream service.

```java
package com.company.saga.common.workflow;

public record HolidayPackageSagaInput(
        UUID sagaId,
        String businessKey,
        String operatorId,
        AirlineBookingRequest airlineBooking,
        HotelBookingRequest hotelBooking,
        CarBookingRequest carBooking) {
}

public record AirlineBookingRequest(
        String flightNumber,
        String originAirportCode,
        String destinationAirportCode,
        LocalDate departureDate,
        Integer passengerCount) {
}

public record HotelBookingRequest(
        String hotelCode,
        String roomType,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        Integer guestCount) {
}

public record CarBookingRequest(
        String carCategory,
        String pickupLocation,
        LocalDate pickupDate,
        LocalDate dropoffDate) {
}
```

### `operatorId` vs. `businessKey`

These are kept **flat and independent**, exactly like `sagaId`/`businessKey` today:

- **`sagaId`** — the Workflow Id, minted server-side by `booking-service`. Same idempotent-execution role as today.
- **`businessKey`** — the package's own idempotency key, client-supplied-or-generated exactly like `CreateOrderRequestBody.businessKey`/`hasNoBusinessKey()`. Globally unique across the system, independent of who sold the package.
- **`operatorId`** — pure business metadata: which tour operator/agency assembled this package. Carried on `HolidayPackage` (the `booking-service` aggregate) and on `HolidayPackageSagaInput`, but **not** part of the idempotency mechanism — it doesn't gate uniqueness, isn't the Workflow Id, and isn't validated against any operator registry (none exists at this phase). It rides along for future reporting/filtering.

Two alternatives were considered and rejected as premature for phase 1:
- Namespacing `businessKey` as `"{operatorId}:{operatorReference}"` — adds parsing/coupling for no phase-1 benefit; there's no multi-operator collision scenario yet to protect against.
- A composite `(operatorId, businessKey)` uniqueness constraint at persistence — same reasoning.

## 3. Workflow interface

```java
package com.company.saga.common.workflow;

@WorkflowInterface
public interface HolidayPackageSagaWorkflow {

    @WorkflowMethod
    void process(HolidayPackageSagaInput input);
}

public final class SagaTaskQueues {
    public static final String HOLIDAY_PACKAGE_SAGA = "holiday-package-saga-task-queue";
    private SagaTaskQueues() { }
}
```

Illustrative implementation — this is the crux of the comparison, and belongs directly next to `OrderSagaWorkflowImpl`'s four sequential lines in whatever doc/README eventually explains the pair of repos:

```java
public class HolidayPackageSagaWorkflowImpl implements HolidayPackageSagaWorkflow {

    private static final ActivityOptions ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .build();

    private final AirlineActivities airlineActivities = Workflow.newActivityStub(AirlineActivities.class, ACTIVITY_OPTIONS);
    private final HotelActivities hotelActivities = Workflow.newActivityStub(HotelActivities.class, ACTIVITY_OPTIONS);
    private final CarActivities carActivities = Workflow.newActivityStub(CarActivities.class, ACTIVITY_OPTIONS);
    private final BookingActivities bookingActivities = Workflow.newActivityStub(BookingActivities.class, ACTIVITY_OPTIONS);

    @Override
    public void process(final HolidayPackageSagaInput input) {
        final Promise<Void> airlinePromise = Async.procedure(
                airlineActivities::reserveFlight, input.sagaId(), input.airlineBooking());
        final Promise<Void> hotelPromise = Async.procedure(
                hotelActivities::reserveRoom, input.sagaId(), input.hotelBooking());
        final Promise<Void> carPromise = Async.procedure(
                carActivities::reserveCar, input.sagaId(), input.carBooking());

        Promise.allOf(airlinePromise, hotelPromise, carPromise).get();

        bookingActivities.confirmPackage(input.sagaId());
    }
}
```

The exact failure semantics of `Promise.allOf(...)` under partial failure (fail fast on the first failed leg vs. wait for all three to settle; whether still-in-flight siblings get cancelled) are **intentionally left unpinned** in this phase — see §6/§8, since that question only matters once compensation is in scope.

## 4. Controller / web layer

**`booking-service`** (new — the entry-point analog of `order-service`; a dedicated aggregate owner rather than one of the three reservation services doubling up, since otherwise there's no root to `POST` against or to observe "did the saga complete" the way `order_db.customer_order` does today):

- `POST /holiday-packages` — mints `sagaId`, resolves `businessKey` via the existing `hasNoBusinessKey()` pattern, persists `HolidayPackage` as `PENDING`, starts `HolidayPackageSagaWorkflow` via `WorkflowClient.start(...)` (Workflow Id = `businessKey`, Task Queue = `SagaTaskQueues.HOLIDAY_PACKAGE_SAGA`), returns `202 Accepted` with `{id, businessKey}`. Direct structural mirror of `OrderController.createOrder`/`OrderCreationHandler`.
- `POST /holiday-packages/{sagaId}/confirm` — called by the Workflow's `BookingActivities.confirmPackage` once all 3 legs have completed; marks `HolidayPackage` `CONFIRMED`; idempotent by `sagaId`. Mirrors `OrderController.confirmOrder`.

**Leaf reservation services**, called by the corresponding Activities (mirroring `InventoryController`/`PaymentController`'s role) — each a **single terminal call**, not a two-phase reserve/confirm pair:

- `airline-service`: `POST /airline/reservations` — body `{sagaId, flightNumber, originAirportCode, destinationAirportCode, departureDate, passengerCount}` → `201 Created`, idempotent by `sagaId`.
- `hotel-service`: `POST /hotel/reservations` — body `{sagaId, hotelCode, roomType, checkInDate, checkOutDate, guestCount}` → `201 Created`, idempotent by `sagaId`.
- `car-service`: `POST /car/reservations` — body `{sagaId, carCategory, pickupLocation, pickupDate, dropoffDate}` → `201 Created`, idempotent by `sagaId`.

Why single-call instead of `inventory-service`'s `reserveStock`/`confirmReservation` split: that split exists *because* something (payment) happens between those two calls in the sequential flow. In the parallel model, nothing gates an individual leg between its own reservation call and the overall package-level join — so each leg only needs one terminal call. Only `booking-service` gets the two-phase create/confirm shape, because it's the aggregate that genuinely has an intermediate state (`PENDING` while the 3 promises are outstanding).

## 5. Module layout

```
temporal-saga-pattern-parallel/
├── saga-common/                     Spring-free: error/ (SagaException/TemporarySagaException/PermanentSagaException,
│                                     carried over unchanged) + workflow/ (HolidayPackageSagaWorkflow,
│                                     HolidayPackageSagaInput + AirlineBookingRequest/HotelBookingRequest/
│                                     CarBookingRequest, SagaTaskQueues)
├── booking-service/                 domain (HolidayPackage, HolidayPackageStatus) / persistence / service
│                                     (HolidayPackageProgressionService) / web (POST /holiday-packages,
│                                     POST /holiday-packages/{sagaId}/confirm) — starts the Workflow
├── airline-service/                 domain / persistence / service / web (POST /airline/reservations)
├── hotel-service/                   domain / persistence / service / web (POST /hotel/reservations)
├── car-service/                     domain / persistence / service / web (POST /car/reservations)
├── saga-orchestrator-temporal/      workflow/HolidayPackageSagaWorkflowImpl (Async.procedure + Promise.allOf) +
│                                     activities/ (AirlineActivities, HotelActivities, CarActivities,
│                                     BookingActivities, each with a WebClient-based *Impl) +
│                                     config/ActivityWebClientConfig (4 WebClient beans)
├── platform-test/                   HolidayPackageParallelEndToEndSagaIT — forks 5 service jars against
│                                     Testcontainers Postgres (booking_db/airline_db/hotel_db/car_db) +
│                                     Testcontainers temporalio/temporal dev server
├── docker/                          Postgres init script (4 DBs) + Temporal dynamicconfig, same pattern as today
├── docker-compose.yml
├── CLAUDE.md
└── docs/
```

| Service | Port |
|---|---|
| `saga-orchestrator-temporal` | 8080 |
| `booking-service` | 8081 |
| `airline-service` | 8082 |
| `hotel-service` | 8083 |
| `car-service` | 8084 |

## 6. Explicitly deferred

Mirroring this repo's own `CLAUDE.md` §"Explicitly deferred":

- **Compensation** — and architecturally, not just "not built yet": there's no clean LIFO stack to unwind since the 3 reservations are concurrent. A future design must first decide how to know which legs actually succeeded before compensating only those, and whether to cancel still-in-flight siblings when one leg fails.
- `releaseFlight`/`releaseRoom`/`releaseCar` endpoints and Activities.
- Retry/backoff (`RetryPolicy` on `ActivityOptions`) and TEMPORARY→PERMANENT classification via a non-retryable `ApplicationFailure`.
- Any pricing/payment concept. Deliberately not porting `payment-service`/`amount` over — all three services here (airline/hotel/car) are reservation-shaped, and introducing payment would reintroduce exactly the "gate on a prior step" dependency this repo exists to contrast away from.
- Operator-based querying/reporting endpoints (`GET /holiday-packages?operatorId=...`).
- Scenario shell scripts equivalent to `platform-test/scripts/e2e-*.sh` beyond one happy-path IT.
- A shared library across `saga-pattern-poc`, `temporal-saga-pattern`, and this repo — same "not yet" as today.

## 7. Maven project structure (to be implemented later)

Not implemented yet — this section fixes the actual `pom.xml` shape closely enough to copy from `temporal-saga-pattern`'s own root/child POMs and rename, so scaffolding the real repo is mechanical rather than another design pass. Same Java 25 / Maven multi-module setup as this repo throughout.

### Root aggregator POM

Same shape as this repo's root `pom.xml`, renamed:

```xml
<groupId>com.company.saga</groupId>
<artifactId>distributed-saga-platform-temporal-parallel</artifactId>
<version>1.0.0-SNAPSHOT</version>
<packaging>pom</packaging>

<modules>
    <module>saga-common</module>
    <module>saga-orchestrator-temporal</module>
    <module>booking-service</module>
    <module>airline-service</module>
    <module>hotel-service</module>
    <module>car-service</module>
    <module>platform-test</module>
</modules>
```

Carried over **unchanged** from this repo's root POM — none of it is specific to the order domain, so there's no reason to redesign it:
- `<properties>`: `java.version=25`, `spring-boot.version=4.1.0`, `temporal.version=1.37.0`, `resilience4j.version=2.4.0`, `lombok.version=1.18.46`, and the pinned plugin versions (`maven-enforcer-plugin`, `maven-compiler-plugin`, `maven-failsafe-plugin`, `jacoco-maven-plugin`).
- `<dependencyManagement>`: the `spring-boot-dependencies` BOM import, plus the `error_prone_annotations`/`guava` version pins — both exist to resolve an internal inconsistency in `temporal-sdk`'s own transitive dependency graph, which applies identically here since the new repo pulls in the same `temporal-sdk`.
- `<pluginManagement>`: `spring-boot-maven-plugin` bound to `verify` with the `exec` classifier (no `spring-boot-starter-parent` inheritance here either), `maven-compiler-plugin` with the Lombok annotation-processor path, `maven-failsafe-plugin`, `jacoco-maven-plugin`.
- The root `<build><plugins>`: `maven-enforcer-plugin` with its `requireJavaVersion`/`requireMavenVersion`/`banDuplicatePomDependencyVersions`/`dependencyConvergence`/`requireUpperBoundDeps` rules (including the same Prometheus/Jackson/Guava exclusion list — those exceptions are about the Spring Boot BOM's own pinning choices, not about the order vs. holiday-package domain), and the root-bound `jacoco-maven-plugin` executions (`prepare-agent` + `report`).

### Child POMs

Each child POM's `<parent>` block points at the renamed root artifact (`distributed-saga-platform-temporal-parallel`). Dependency sets mirror the existing repo's equivalent module one-for-one:

- **`saga-common`** — same shape as today's `saga-common/pom.xml`: only `temporal-sdk` (for `@WorkflowInterface`/`@WorkflowMethod`) plus test-scoped `spring-boot-starter-test`/`assertj-core`. No Spring dependency, same "kept lean" rationale. Houses `HolidayPackageSagaWorkflow`, `HolidayPackageSagaInput` + the three per-service request records, and `SagaTaskQueues` (§2, §3).
- **`booking-service`** — same shape as today's `order-service/pom.xml`: `saga-common` + `lombok` + `spring-boot-starter-webflux` + `springdoc-openapi-starter-webflux-ui` (3.1.0, code-first OpenAPI) + `spring-boot-starter-data-r2dbc` + `temporal-spring-boot-starter` (`WorkflowClient` only — starts the saga, never registers a Worker, same as `order-service`) + `postgresql`(runtime)/`r2dbc-postgresql` + `spring-boot-starter-flyway`/`flyway-database-postgresql` + `micrometer-registry-prometheus`/`micrometer-tracing-bridge-otel` + `spring-boot-starter-actuator`/`spring-boot-starter-validation`, test-scoped `spring-boot-starter-test`/`reactor-test`/`assertj-core`/`temporal-testing` (in-memory Temporal test server, to unit-test the request handler without mocking `WorkflowClient.start(...)`, same as `order-service`'s `OrderCreationHandler` test) + `spring-boot-testcontainers`/`testcontainers-junit-jupiter`/`testcontainers-postgresql`/`testcontainers-r2dbc`. Build plugins: `spring-boot-maven-plugin` + `maven-failsafe-plugin`.
- **`airline-service`, `hotel-service`, `car-service`** — same shape as today's `inventory-service`/`payment-service` POMs: `saga-common` + `lombok` + `spring-boot-starter-webflux` + `springdoc-openapi-starter-webflux-ui` (3.1.0) + `spring-boot-starter-data-r2dbc` + `postgresql`(runtime)/`r2dbc-postgresql` + `spring-boot-starter-flyway`/`flyway-database-postgresql` + `micrometer-registry-prometheus`/`micrometer-tracing-bridge-otel` + `resilience4j-spring-boot4`/`resilience4j-reactor` + `spring-boot-starter-actuator`/`spring-boot-starter-validation`, test-scoped `spring-boot-starter-test`/`reactor-test`/`assertj-core`/`spring-boot-testcontainers`/`testcontainers-junit-jupiter`/`testcontainers-postgresql`/`testcontainers-r2dbc`. **No `temporal-sdk`/`temporal-spring-boot-starter` dependency at all** — like `inventory-service`/`payment-service` today, these are plain REST services the orchestrator's Activities call over `WebClient`; they know nothing about Temporal. Build plugins: `spring-boot-maven-plugin` + `maven-failsafe-plugin`.
- **`saga-orchestrator-temporal`** — same shape as today's `saga-orchestrator-temporal/pom.xml`: `saga-common` + `lombok` + `spring-boot-starter-webflux` (for `WebClient`, plus a reactive server purely so `/actuator/health` responds — no `@RestController` of its own) + `temporal-spring-boot-starter` (registers the Worker — the only module in the platform that does) + `micrometer-registry-prometheus`/`micrometer-tracing-bridge-otel` + `spring-boot-starter-actuator`, test-scoped `spring-boot-starter-test`/`reactor-test`/`assertj-core`/`temporal-testing` (to unit-test `HolidayPackageSagaWorkflowImpl` against fake Activities without a real Temporal Server). Owns no database, so no Postgres/R2DBC/Flyway dependencies at all. Build plugin: `spring-boot-maven-plugin`.
- **`platform-test`** — same shape as today's `platform-test/pom.xml`: `saga-common` (brings in `temporal-sdk` transitively, for asserting the Workflow Execution itself closed as `COMPLETED`) + test-scoped dependencies on all 5 other modules (`booking-service`, `airline-service`, `hotel-service`, `car-service`, `saga-orchestrator-temporal`) purely so Maven's reactor build order guarantees their jars exist before this module's `integration-test` phase runs — `HolidayPackageParallelEndToEndSagaIT` forks each as a real `java -jar` process, same reason as today (identically-named classpath resources across service jars rule out an in-process shared classloader). Plus `postgresql` (JDBC-only, to poll `holiday_package.status` directly) and test-scoped `spring-boot-starter-test`/`assertj-core`/`testcontainers-junit-jupiter`/`testcontainers-postgresql`. Build plugin: `maven-failsafe-plugin`, configured with the same `saga.platform.version` system property so the IT can compute each sibling module's built jar path without hardcoding the version.

### What's still open here

The dependency *shapes* above are fixed by direct analogy to this repo's own modules, but two things still need an explicit decision once scaffolding actually starts (folded into §8's open-items list): the final `groupId`/root `artifactId` naming (`distributed-saga-platform-temporal-parallel` above is a placeholder, not confirmed), and whether `airline-service`/`hotel-service`/`car-service` truly need `resilience4j-spring-boot4`/`resilience4j-reactor` at all — `inventory-service`/`payment-service` carry it today, but neither this repo nor this doc has actually wired it into any saga-facing retry logic yet (§6), so it may be dead weight until that's revisited.

## 8. Open items for refinement

This draft fixes the core interfaces well enough to scaffold from, but a few things are worth another pass before (or shortly after) the repo is created:

1. **Booking field lists** — the fields above (flight number/route/date/passenger count; room type/dates/guest count; car category/pickup/dropoff) are a first pass at "realistic but simple." Revisit once the reservation services are actually being built, in case a field turns out to be unused or something's missing.
2. **`Promise.allOf` partial-failure/cancellation semantics** — deliberately unpinned (§3, §6); needs a decision once compensation is designed.
3. **Filename/location** — this doc currently lives at `docs/temporal-saga-parallel-plan.md` in `temporal-saga-pattern`; once `temporal-saga-pattern-parallel` exists as its own repo, decide whether this doc migrates there (as that repo's own `docs/temporal-saga-parallel-proposal.md`, analogous to `temporal-saga-proposal.md` here) or stays here as the originating design doc.
4. **Root `groupId`/`artifactId` naming** — `com.company.saga:distributed-saga-platform-temporal-parallel` (§7) is a placeholder mirroring this repo's own root coordinates; confirm before scaffolding.
5. **`resilience4j` in the leaf services** — carried over from `inventory-service`/`payment-service`'s POM shape (§7) but not actually wired into any retry logic in either repo yet; decide whether to include it upfront or drop it until it has a use.
