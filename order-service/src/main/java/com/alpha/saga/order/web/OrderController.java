package com.alpha.saga.order.web;

import com.alpha.saga.order.service.CancelOrderRequest;
import com.alpha.saga.order.service.ConfirmOrderRequest;
import com.alpha.saga.order.service.OrderProgressionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** REST entry points for the order lifecycle: creation (starts the saga) and confirmation (called by the saga Workflow). */
@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Order lifecycle — creation starts the saga, confirmation is invoked by it")
@Slf4j
public class OrderController {

    private final OrderCreationHandler orderCreationHandler;
    private final OrderProgressionService orderProgressionService;
    private final OrderQueryHandler orderQueryHandler;

    public OrderController(final OrderCreationHandler orderCreationHandler,
            final OrderProgressionService orderProgressionService,
            final OrderQueryHandler orderQueryHandler) {
        this.orderCreationHandler = Objects.requireNonNull(orderCreationHandler, "orderCreationHandler must not be null");
        this.orderProgressionService = Objects.requireNonNull(orderProgressionService, "orderProgressionService must not be null");
        this.orderQueryHandler = Objects.requireNonNull(orderQueryHandler, "orderQueryHandler must not be null");
    }

    @PostMapping
    @Operation(
            summary = "Create an order",
            description = "Mints a sagaId, creates the order, and starts the order saga Workflow.")
    @ApiResponse(responseCode = "202", description = "Order accepted; the saga Workflow will proceed asynchronously")
    @ApiResponse(responseCode = "400", description = "Invalid request body (blank sku, non-positive quantity/amount, ...)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public Mono<ResponseEntity<CreateOrderResponseBody>> createOrder(@RequestBody final CreateOrderRequestBody request) {
        log.debug("createOrder - {}", request);

        return orderCreationHandler.createOrder(request)
                .map(response -> ResponseEntity.status(HttpStatus.ACCEPTED).body(response));
    }

    @PostMapping("/{sagaId}/confirm")
    @Operation(
            summary = "Confirm an order",
            description = "Called by the order saga Workflow's OrderActivities once payment has completed. Idempotent by sagaId.")
    @ApiResponse(responseCode = "200", description = "Order confirmed (or already was)")
    public Mono<ResponseEntity<ConfirmOrderResponseBody>> confirmOrder(
            @PathVariable("sagaId") @Parameter(description = "The saga id, also the order's id") final UUID sagaId) {
        log.debug("confirmOrder - {}", sagaId);

        return orderProgressionService.confirmOrder(new ConfirmOrderRequest(sagaId, Instant.now()))
                .map(order -> ResponseEntity.ok(new ConfirmOrderResponseBody(order.id(), order.status())));
    }

    @PostMapping("/{sagaId}/cancel")
    @Operation(
            summary = "Cancel an order",
            description = "Compensation: called by the saga Workflow when a later step permanently fails. Idempotent by sagaId.")
    @ApiResponse(responseCode = "200", description = "Order cancelled (or already was)")
    public Mono<ResponseEntity<CancelOrderResponseBody>> cancelOrder(
            @PathVariable("sagaId") @Parameter(description = "The saga id, also the order's id") final UUID sagaId) {
        log.debug("cancelOrder - {}", sagaId);

        return orderProgressionService.cancelOrder(new CancelOrderRequest(sagaId, Instant.now()))
                .map(order -> ResponseEntity.ok(new CancelOrderResponseBody(order.id(), order.status())));
    }

    @GetMapping("/{sagaId}")
    @Operation(
            summary = "Get order status",
            description = "Reads the order's durable status; while still PENDING, also reads live saga progress from the Workflow.")
    @ApiResponse(responseCode = "200", description = "Current order/saga status")
    @ApiResponse(responseCode = "404", description = "No order with this sagaId",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public Mono<ResponseEntity<OrderStatusResponseBody>> getOrderStatus(
            @PathVariable("sagaId") @Parameter(description = "The saga id, also the order's id") final UUID sagaId) {
        log.debug("getOrderStatus - {}", sagaId);

        return orderQueryHandler.getOrderStatus(sagaId).map(ResponseEntity::ok);
    }
}
