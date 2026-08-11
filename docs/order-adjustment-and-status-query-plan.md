# Two design sketches: partial-quantity order adjustment ops, and a live saga status query

> Status: **Both parts are implemented.** Part A (partial-quantity adjustment): `inventory-service`'s `InventoryProgressionService.creditStock`/`InventoryController` and `payment-service`'s `PaymentProgressionService.issuePartialRefund`/`PaymentController` — the adjustment saga/workflow that would call the two together is still out of scope (see its own note below). Covered by `CreditStockRequestTest`, `InventoryProgressionServiceTest`, `InventoryControllerTest`, `IssuePartialRefundRequestTest`, `PaymentProgressionServiceTest`, `PaymentControllerTest`, and `platform-test/scripts/e2e-order-adjustment.sh` (all passing). Part B (`GET /orders/{sagaId}` status query): `saga-common`'s `OrderSagaProgress`/`OrderSagaWorkflow.getProgress()`, `saga-orchestrator-temporal`'s `OrderSagaWorkflowImpl`, and `order-service`'s `OrderQueryHandler`/`OrderController`. Covered by `OrderQueryHandlerTest`, `OrderControllerTest`, `OrderSagaWorkflowImplTest`, `OrderNotFoundExceptionTest`, and `platform-test/scripts/e2e-order-status-query.sh` (all passing).

This plan covers two independent, unrelated additions. They touch different modules and can be implemented separately.

---

## Part A — Partial-quantity adjustment: `creditStock` and `issuePartialRefund`

### Context

The conversation explored adding an "order quantity adjustment" saga (place order for `Q1`, later place a related adjustment for `Q2`/`Q3`, charge/refund the difference). Investigation of `inventory-service` and `payment-service` found that neither `InventoryReservation.release()` nor `Payment.refund()` can back this: both are 1:1-with-`sagaId` aggregates tied to *their own* saga's full amount, and `InventoryReservation`'s `CONFIRMED` state is an explicit terminal PIVOT (`ReservationStatus.java:13-14`) with no reversal path once an order is completed.

The design that avoids fighting that constraint: an adjustment is its own **new saga** (new `sagaId`), never mutating the original `InventoryReservation`/`Payment` rows. An *increase* reuses the existing `reserveStock`/`requestPayment` use cases as-is against the new `sagaId` — no new code needed. A *decrease* needs one new use case per service, because `releaseStock`/`refundPayment` both require a pre-existing reservation/payment row for that `sagaId`, which a decrease-only adjustment saga will never create. This part sketches those two new use cases only (not the adjustment workflow itself, which is a separate, later piece of work).

### `inventory-service`: `creditStock`

Credits stock back to the shared `StockItem` counter for an arbitrary quantity, bypassing the per-saga `InventoryReservation` aggregate entirely (there is nothing to load — a decrease adjustment never reserved anything).

- **Domain**: no new domain type needed — `StockItem.release(quantity)` (`StockItem.java:48-51`) already credits an arbitrary quantity; it just isn't reachable today except via `InventoryProgressionService.creditStockAndRelease`, which is coupled to an `InventoryReservation`.
- **`service/CreditStockRequest.java`** (new record, same shape as `ReserveStockRequest.java:8-25`): `(UUID sagaId, String sku, Integer quantity, Instant now)` — `sagaId` here identifies the *adjustment*, kept for logging/idempotency symmetry even though this use case writes no reservation row. Compact constructor: null checks + blank-sku/non-positive-quantity `IllegalArgumentException`, mirroring `ReserveStockRequest`.
- **`InventoryProgressionService.creditStock(CreditStockRequest request)`** (new method, alongside `reserveStock`/`confirmReservation`/`releaseStock`):
  ```java
  public Mono<Void> creditStock(final CreditStockRequest request) {
      log.debug("creditStock - {}", request);
      return loadStockItem(request.sku())
              .map(stockItem -> stockItem.release(request.quantity()))
              .flatMap(stockItemRepository::save)
              .then();
  }
  ```
  Reuses the existing private `loadStockItem` helper (`InventoryProgressionService.java:144-147`) unchanged. No idempotency guard is possible here (there's no row keyed by `sagaId` to check against) — the caller (the adjustment workflow, via `Saga`'s own exactly-once-per-attempt semantics plus Temporal's Activity retry/dedup) is responsible for not calling this twice for the same adjustment. Worth flagging explicitly in the javadoc, same tone as the existing "no real warehouse gateway" caveats in this class.
- **`web/CreditStockRequestBody.java`** (new record, mirrors `ReserveStockRequestBody.java:9-27`): `(UUID sagaId, String sku, Integer quantity)`, same `@Schema` annotations and validation.
- **`web/InventoryController.java`**: new endpoint under the existing `/inventory/reservations` mapping, e.g. `POST /inventory/reservations/credit` (no `{sagaId}` path segment — deliberately *not* `/{sagaId}/credit`, since this isn't acting on an existing reservation resource):
  ```java
  @PostMapping("/credit")
  @Operation(summary = "Credit stock", description = "Credits stock back for a quantity decrease on an already-completed order, without an existing reservation.")
  @ApiResponse(responseCode = "200", description = "Stock credited")
  public Mono<ResponseEntity<Void>> creditStock(@RequestBody final CreditStockRequestBody request) {
      log.debug("creditStock - {}", request);
      return inventoryProgressionService.creditStock(new CreditStockRequest(request.sagaId(), request.sku(), request.quantity(), Instant.now()))
              .thenReturn(ResponseEntity.ok().build());
  }
  ```
- **Errors**: throws only `IllegalStateException` (stock item not found — mirrors existing `loadStockItem` behavior) — no new exception type, so `RestExceptionHandler` needs no change.

### `payment-service`: `issuePartialRefund`

Refunds an arbitrary amount without requiring a pre-existing `Payment` row for the sagaId in use.

- **Domain**: `Payment` is unsuitable to reuse directly — its constructor requires a full lifecycle (`request` → `complete` → `refund`) and its `refund()` always refunds the *entire* stored `amount`. Introduce a **separate, minimal aggregate** rather than distorting `Payment`: `PartialRefund` record — `(UUID id, UUID relatedSagaId, BigDecimal amount, Instant createdAt, Long version)`, table `partial_refund`, `@Id id` = the adjustment's own `sagaId` (same "id is the saga's id" convention as `Payment`/`InventoryReservation`). No status enum needed — a partial refund is a single fire-and-forget event, not a stateful transition (unlike `Payment`, nothing later reverses a refund). The `@Version version` field (always `null` on creation) is required despite there being nothing to optimistically lock: without it, Spring Data R2DBC's `save()` sees the always-non-null, manually-assigned `id` and assumes the row already exists, issuing a silent no-op `UPDATE` instead of an `INSERT` — caught by `platform-test/scripts/e2e-order-adjustment.sh` (a unit test with a fully-mocked repository can't catch this; it needs a real database). Needs its own Flyway migration (append-only once released, per repo convention) adding `partial_refund(id UUID PRIMARY KEY, related_saga_id UUID NOT NULL, amount NUMERIC NOT NULL, created_at TIMESTAMP NOT NULL, version BIGINT NOT NULL DEFAULT 0)`.
- **`persistence/PartialRefundRepository.java`** (new, mirrors `PaymentRepository.java:9`): `interface PartialRefundRepository extends ReactiveCrudRepository<PartialRefund, UUID>`.
- **`service/IssuePartialRefundRequest.java`** (new record, mirrors `RequestPaymentRequest.java:9-21`): `(UUID sagaId, BigDecimal amount, Instant now)`, same `amount.signum() <= 0` validation.
- **`PaymentProgressionService.issuePartialRefund(IssuePartialRefundRequest request)`** (new method):
  ```java
  public Mono<PartialRefund> issuePartialRefund(final IssuePartialRefundRequest request) {
      log.debug("issuePartialRefund - {}", request);
      return partialRefundRepository.findById(request.sagaId())
              .switchIfEmpty(Mono.defer(() -> partialRefundRepository.save(
                      new PartialRefund(request.sagaId(), request.sagaId(), request.amount(), request.now(), null))));
  }
  ```
  `findById`-then-`switchIfEmpty` gives idempotency-by-`sagaId` for free, matching every other use case in this repo (`requestPayment`, `reserveStock`, `createOrder`). `relatedSagaId` in the sketch above just equals the adjustment's own `sagaId` for now — if the plan later needs to link back to the *original* order's sagaId (for traceability/auditing), thread that through as a separate field on `IssuePartialRefundRequest` once the adjustment workflow's input shape is designed; not needed for this sketch.
- **`web/IssuePartialRefundRequestBody.java`** (new, mirrors `RequestPaymentRequestBody.java:10-23`): `(UUID sagaId, BigDecimal amount)`.
- **`web/PartialRefundResponseBody.java`** (new, mirrors `PaymentResponseBody.java:10-20`): `(UUID sagaId, BigDecimal amount)`.
- **`web/PaymentController.java`**: new endpoint, e.g. `POST /payments/partial-refunds` (a distinct resource, not nested under `/payments/{sagaId}/...`, since it's deliberately not acting on an existing `Payment`):
  ```java
  @PostMapping("/partial-refunds")
  @Operation(summary = "Issue a partial refund", description = "Refunds an amount for a quantity decrease on an already-completed order, without a corresponding payment request.")
  @ApiResponse(responseCode = "201", description = "Partial refund issued")
  public Mono<ResponseEntity<PartialRefundResponseBody>> issuePartialRefund(@RequestBody final IssuePartialRefundRequestBody request) {
      log.debug("issuePartialRefund - {}", request);
      return paymentProgressionService.issuePartialRefund(new IssuePartialRefundRequest(request.sagaId(), request.amount(), Instant.now()))
              .map(refund -> ResponseEntity.status(HttpStatus.CREATED).body(new PartialRefundResponseBody(refund.id(), refund.amount())));
  }
  ```
- **Errors**: none new — `PermanentSagaException`/`TemporarySagaException` remain `final` (confirmed: `saga-common/.../error/TemporarySagaException.java:4`, `PermanentSagaException.java:4`) so any future fault-injection for this path throws them directly, same as every other use case; `RestExceptionHandler` needs no change.

### Verification (Part A)

- Unit tests for `InventoryProgressionService.creditStock` and `PaymentProgressionService.issuePartialRefund` (in-memory/mocked repositories), plus dedicated `CreditStockRequestTest`/`IssuePartialRefundRequestTest` for the two new service-layer request records, matching this repo's convention of one test class per `*Request` record (e.g. `ReserveStockRequestTest`, `RequestPaymentRequestTest`).
- `InventoryControllerTest`/`PaymentControllerTest`: `WebTestClient` cases for the two new endpoints (happy path + validation-failure 400s).
- The `partial_refund` Flyway migration (`V2__create_partial_refund.sql`) is picked up automatically by `payment-service`'s existing Testcontainers-Postgres IT setup.
- **`platform-test/scripts/e2e-order-adjustment.sh`** (new, added to the existing `e2e-*.sh` collection): curl-based, against the real docker-compose stack. Calls `POST /inventory/reservations/credit` directly and asserts `stock_item.available_quantity` increases by exactly the credited quantity; calls `POST /payments/partial-refunds` and asserts a single `partial_refund` row is created, then re-issues the same request and asserts it's a no-op (idempotent by the adjustment's own `sagaId`, not a second row). Exercises the two endpoints directly rather than through a saga, since the adjustment workflow that would call them together doesn't exist yet.
- No end-to-end/workflow test for a full adjustment saga, since that workflow itself is still out of scope for this sketch (see the design note above).

---

## Part B — `GET /orders/{sagaId}`: live saga progress via a Temporal `@QueryMethod`

### Context

`order-service` currently has no read endpoint at all — a caller can't see saga progress except via the Temporal Web UI. `saga-pattern-poc`'s custom orchestrator would need a `saga_step`-table read model for this; here, Temporal's own `@QueryMethod` mechanism (a synchronous, side-effect-free read of live Workflow state) does it without any new table. Repo-wide grep confirms no `@QueryMethod` exists anywhere yet, and `OrderSagaWorkflow` (`saga-common/.../workflow/OrderSagaWorkflow.java:11-16`) currently declares only `@WorkflowMethod void process(OrderSagaInput input)`.

Key existing facts that shape this design:
- `CustomerOrder` (`order-service/.../domain/CustomerOrder.java`) already persists a coarse `status` (`PENDING`/`CONFIRMED`/`CANCELLED`) — durable and always queryable, but too coarse to show *which step* an in-flight saga is on.
- The Workflow's `WorkflowId` is the order's `businessKey` (`OrderCreationHandler.java:69-74`), **not** the `sagaId` — and `businessKey` can differ from `sagaId.toString()` when the caller supplies a custom one at creation (`CreateOrderRequestBody`). So `GET /orders/{sagaId}` must first resolve `businessKey` from the `CustomerOrder` row before it can address the Workflow.
- `OrderSagaWorkflowImpl` (`saga-orchestrator-temporal/.../OrderSagaWorkflowImpl.java`) currently tracks no progress state at all — a new field is needed.
- `WorkflowClient` is auto-configured by `temporal-spring-boot-starter` (confirmed via `OrderWebConfig.java:14-16`) and already injected directly into `OrderCreationHandler`; the new query path follows the same pattern.

### Design

**1. `saga-common`: new shared progress type + query method**

`workflow/OrderSagaProgress.java` (new enum, shared between the Workflow implementation and `order-service`, same package as `OrderSagaWorkflow`):
```java
public enum OrderSagaProgress {
    STARTED,
    INVENTORY_RESERVED,
    PAYMENT_REQUESTED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED
}
```
Mirrors `OrderSagaWorkflowImpl.process()`'s actual step sequence (`reserveStock` → `requestPayment` → `confirmOrder`+`confirmReservation` → done; on failure: compensate → cancel).

`workflow/OrderSagaWorkflow.java`: add
```java
@QueryMethod
OrderSagaProgress getProgress();
```
alongside the existing `@WorkflowMethod`.

**2. `saga-orchestrator-temporal`: track and expose progress**

`OrderSagaWorkflowImpl.java` — add a private mutable field (query methods read workflow-local state directly, no `Workflow.await`/blocking involved):
```java
private OrderSagaProgress progress = OrderSagaProgress.STARTED;

@Override
public OrderSagaProgress getProgress() {
    return progress;
}
```
Update `progress` at each transition point in `process()`:
```java
inventoryActivities.reserveStock(input.sagaId(), input.sku(), input.quantity());
saga.addCompensation(inventoryActivities::releaseStock, input.sagaId());
progress = OrderSagaProgress.INVENTORY_RESERVED;

paymentActivities.requestPayment(input.sagaId(), input.amount());
saga.addCompensation(paymentActivities::refundPayment, input.sagaId());
progress = OrderSagaProgress.PAYMENT_REQUESTED;

orderActivities.confirmOrder(input.sagaId());
inventoryActivities.confirmReservation(input.sagaId());
progress = OrderSagaProgress.COMPLETED;
} catch (final RuntimeException error) {
    progress = OrderSagaProgress.COMPENSATING;
    saga.compensate();
    orderActivities.cancelOrder(input.sagaId());
    progress = OrderSagaProgress.COMPENSATED;
    throw error;
}
```
This is a pure in-workflow field mutation — deterministic, replay-safe, no new Activities.

**3. `order-service`: resolve businessKey, query, respond**

`OrderProgressionService.java` — add a small public read method reusing the existing private `loadOrder` helper (`OrderProgressionService.java:89-92`):
```java
public Mono<CustomerOrder> getOrder(final UUID sagaId) {
    log.debug("getOrder - {}", sagaId);
    return loadOrder(sagaId);
}
```
`loadOrder` already errors with `IllegalStateException("Order not found: ...")` on a missing row — good enough to reuse, but that maps to a 500 today via the generic backstop; Part B needs it to map to 404 (see below), so introduce a small dedicated `OrderNotFoundException` (unchecked) thrown by `loadOrder` instead of the bare `IllegalStateException`, and map it explicitly. (This is a small, low-risk cleanup of existing behavior needed to give `GET /orders/{sagaId}` a proper 404 — flagging it rather than silently reusing 500 for "order doesn't exist.")

New **`OrderQueryHandler.java`** (new class, mirrors `OrderCreationHandler`'s shape and its `WorkflowClient`/blocking-call pattern):
```java
public class OrderQueryHandler {

    private final OrderProgressionService orderProgressionService;
    private final WorkflowClient workflowClient;
    private final Scheduler blockingCallScheduler;

    // constructor: same null-check pattern as OrderCreationHandler

    public Mono<OrderStatusResponseBody> getOrderStatus(final UUID sagaId) {
        log.debug("getOrderStatus - {}", sagaId);
        return orderProgressionService.getOrder(sagaId)
                .flatMap(order -> switch (order.status()) {
                    case CONFIRMED -> Mono.just(new OrderStatusResponseBody(order.id(), order.status(), OrderSagaProgress.COMPLETED));
                    case CANCELLED -> Mono.just(new OrderStatusResponseBody(order.id(), order.status(), OrderSagaProgress.COMPENSATED));
                    case PENDING -> queryWorkflowProgress(order);
                });
    }

    private Mono<OrderStatusResponseBody> queryWorkflowProgress(final CustomerOrder order) {
        final OrderSagaWorkflow workflow = workflowClient.newWorkflowStub(OrderSagaWorkflow.class, order.businessKey());
        return Mono.fromCallable(workflow::getProgress)
                .subscribeOn(blockingCallScheduler)
                .map(progress -> new OrderStatusResponseBody(order.id(), order.status(), progress));
    }
}
```
Deliberately queries Temporal **only** while the order is still `PENDING` — once `CustomerOrder.status` is terminal, the outcome is derived from the durable DB row alone, without depending on the Workflow Execution still being open/queryable in Temporal's retention window. This reuses `blockingCallScheduler`, the same virtual-thread executor bean `OrderCreationHandler` already uses for its own blocking Temporal call (`OrderWebConfig.java:35-38`) — no new bean needed, just an added constructor parameter on the config method.

`web/OrderStatusResponseBody.java` (new record, same `@Schema`-annotated shape as `ConfirmOrderResponseBody`/`CancelOrderResponseBody`): `(UUID sagaId, OrderStatus orderStatus, OrderSagaProgress sagaProgress)`.

**4. `OrderController.java`** — new endpoint alongside the three existing `@PostMapping`s:
```java
@GetMapping("/{sagaId}")
@Operation(summary = "Get order status", description = "Reads the order's current status; while still PENDING, also reads live saga progress from the Workflow.")
@ApiResponse(responseCode = "200", description = "Current order/saga status")
@ApiResponse(responseCode = "404", description = "No order with this sagaId", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public Mono<ResponseEntity<OrderStatusResponseBody>> getOrderStatus(
        @PathVariable("sagaId") @Parameter(description = "The saga id, also the order's id") final UUID sagaId) {
    log.debug("getOrderStatus - {}", sagaId);
    return orderQueryHandler.getOrderStatus(sagaId).map(ResponseEntity::ok);
}
```

**5. `RestExceptionHandler.java`** (order-service) — add:
```java
@ExceptionHandler(OrderNotFoundException.class)
public Mono<ResponseEntity<Object>> handleOrderNotFoundException(final OrderNotFoundException ex, final ServerWebExchange exchange) {
    final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    return handleExceptionInternal(ex, problemDetail, new HttpHeaders(), HttpStatus.NOT_FOUND, exchange);
}
```

**6. `OrderWebConfig.java`** — add an `orderQueryHandler` bean, same shape as the existing `orderCreationHandler` bean, reusing `orderSagaStartScheduler` (or split into a differently-named scheduler bean if query load ever warrants isolating it from the start path — not needed at this scale).

### Verification (Part B)

- Unit tests: `OrderProgressionServiceTest` for `getOrder`/`OrderNotFoundException`; a new `OrderQueryHandlerTest` mocking `WorkflowClient`/`OrderSagaWorkflow` stub for both the terminal-status short-circuit and the live-query path.
- `OrderControllerTest`: add a `GET /orders/{sagaId}` case for 200 (both terminal and pending) and 404.
- `TemporalEndToEndSagaIT` (or a new IT): after starting a real saga, poll `GET /orders/{sagaId}` and assert `sagaProgress` advances through `STARTED`/`INVENTORY_RESERVED`/`PAYMENT_REQUESTED`/`COMPLETED` — this is the one place worth an end-to-end check, since it's the only way to confirm the `@QueryMethod` actually round-trips through a real Temporal Server rather than just compiling.
- **`platform-test/scripts/e2e-order-status-query.sh`** (new, added to the existing `e2e-*.sh` collection alongside `e2e-happy-path.sh` etc.): curl-based, against the real docker-compose stack. Runs the same fault-free happy-path input as `e2e-happy-path.sh`, `GET /orders/{sagaId}` once early (logged, not asserted — racy against how fast the local saga actually completes) and once after `wait_order_status` reaches `CONFIRMED` (asserted: `200`, `orderStatus=CONFIRMED`, `sagaProgress=COMPLETED`), plus a `GET /orders/{unknown-sagaId}` asserting `404`.
- Manually: run the full stack (`platform-test/scripts/start-all.sh`), `POST /orders`, then `GET /orders/{sagaId}` a few times while it's in flight, and confirm the reported `sagaProgress` matches what the Temporal Web UI's event history shows for that Workflow Execution.
