package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.adapter.in.web.security.TerminalPrincipal;
import com.fleet.storefront.application.port.in.CancelOrderUseCase;
import com.fleet.storefront.application.port.in.CancelOrderUseCase.CancelOrderCommand;
import com.fleet.storefront.application.port.in.FindOrdersQuery;
import com.fleet.storefront.application.port.in.Limit;
import com.fleet.storefront.application.port.in.OrderNotFoundException;
import com.fleet.storefront.application.port.in.PayOrderUseCase;
import com.fleet.storefront.application.port.in.PayOrderUseCase.PayOrderCommand;
import com.fleet.storefront.application.port.in.PlaceOrderCommand;
import com.fleet.storefront.application.port.in.PlaceOrderResult;
import com.fleet.storefront.application.port.in.PlaceOrderUseCase;
import com.fleet.storefront.application.port.in.RefundOrderUseCase;
import com.fleet.storefront.application.port.in.RefundOrderUseCase.RefundOrderCommand;
import com.fleet.storefront.application.port.in.VoidPaymentUseCase;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.OrderStatus;
import com.fleet.storefront.domain.order.Quantity;
import com.fleet.storefront.domain.payment.PaymentId;
import com.fleet.storefront.domain.payment.PaymentMethod;
import com.fleet.storefront.domain.shared.DomainException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Checkout and order lifecycle for local terminals.
 *
 * <p>Every state change carries an {@code Idempotency-Key} generated once per logical action and resent
 * unchanged on every retry. A retry of a committed action returns {@code 200} with
 * {@code Idempotent-Replayed: true}; the first successful attempt returns {@code 201} (or {@code 200} for
 * a cancellation). The acting terminal is always the authenticated device.
 */
@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final PlaceOrderUseCase placeOrder;
    private final FindOrdersQuery findOrders;
    private final PayOrderUseCase payOrder;
    private final CancelOrderUseCase cancelOrder;
    private final RefundOrderUseCase refundOrder;
    private final VoidPaymentUseCase voidPayment;
    private final MeterRegistry meters;

    OrderController(PlaceOrderUseCase placeOrder, FindOrdersQuery findOrders, PayOrderUseCase payOrder,
                    CancelOrderUseCase cancelOrder, RefundOrderUseCase refundOrder, VoidPaymentUseCase voidPayment,
                    MeterRegistry meters) {
        this.placeOrder = placeOrder;
        this.findOrders = findOrders;
        this.payOrder = payOrder;
        this.cancelOrder = cancelOrder;
        this.refundOrder = refundOrder;
        this.voidPayment = voidPayment;
        this.meters = meters;
    }

    @PostMapping
    ResponseEntity<OrderResponse> place(@AuthenticationPrincipal TerminalPrincipal device,
                                        @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                        @Valid @RequestBody PlaceOrderRequest request) {
        PlaceOrderCommand command = Requests.parse(() -> new PlaceOrderCommand(
                device.terminalId(),
                idempotencyKey,
                request.items().stream()
                        .map(i -> new PlaceOrderCommand.Item(Sku.of(i.sku()), Quantity.of(i.quantity())))
                        .toList()));
        PlaceOrderResult result = timed("checkout", () -> placeOrder.placeOrder(command), r -> r.replayed());
        return respond(result.replayed(), HttpStatus.CREATED, OrderResponse.from(result.account()));
    }

    @GetMapping("/{id}")
    OrderResponse get(@PathVariable UUID id) {
        OrderId orderId = new OrderId(id);
        return findOrders.byId(orderId).map(OrderResponse::from).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @GetMapping
    List<OrderResponse> recent(@RequestParam(defaultValue = "20") int limit,
                               @RequestParam(required = false) OrderStatus status) {
        Limit max = Requests.parse(() -> new Limit(limit));
        return findOrders.recent(max, Optional.ofNullable(status)).stream().map(OrderResponse::from).toList();
    }

    @PostMapping("/{id}/payments")
    ResponseEntity<PaymentResponseBody> pay(@AuthenticationPrincipal TerminalPrincipal device,
                                            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                            @PathVariable UUID id, @Valid @RequestBody PaymentRequest request) {
        PayOrderCommand command = Requests.parse(() -> new PayOrderCommand(device.terminalId(), idempotencyKey,
                new OrderId(id), request.method(), request.amount(), request.tendered(), request.reference()));
        PayOrderUseCase.PaymentResult result = timed("payment", () -> payOrder.pay(command), r -> r.replayed());
        return respond(result.replayed(), HttpStatus.CREATED, new PaymentResponseBody(
                OrderResponse.PaymentResponse.from(result.payment()), OrderResponse.from(result.account())));
    }

    @PostMapping("/{id}/payments/{paymentId}/void")
    ResponseEntity<OrderResponse> voidPayment(@AuthenticationPrincipal TerminalPrincipal device,
                                              @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                              @PathVariable UUID id, @PathVariable UUID paymentId,
                                              @Valid @RequestBody CancelRequest request) {
        VoidPaymentUseCase.VoidPaymentCommand command = Requests.parse(() -> new VoidPaymentUseCase.VoidPaymentCommand(
                device.terminalId(), idempotencyKey, new OrderId(id), new PaymentId(paymentId), request.reason()));
        VoidPaymentUseCase.VoidResult result = timed("void-payment", () -> voidPayment.voidPayment(command),
                r -> r.replayed());
        return respond(result.replayed(), HttpStatus.OK, OrderResponse.from(result.account()));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<OrderResponse> cancel(@AuthenticationPrincipal TerminalPrincipal device,
                                         @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                         @PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        CancelOrderCommand command = Requests.parse(() -> new CancelOrderCommand(device.terminalId(), idempotencyKey,
                new OrderId(id), request.reason()));
        CancelOrderUseCase.CancelResult result = timed("cancel", () -> cancelOrder.cancel(command), r -> r.replayed());
        return respond(result.replayed(), HttpStatus.OK, OrderResponse.from(result.account()));
    }

    @PostMapping("/{id}/refunds")
    ResponseEntity<RefundResponseBody> refund(@AuthenticationPrincipal TerminalPrincipal device,
                                              @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                              @PathVariable UUID id, @Valid @RequestBody RefundRequest request) {
        RefundOrderCommand command = Requests.parse(() -> {
            TreeMap<Integer, Integer> units = new TreeMap<>();
            request.lines().forEach(l -> units.merge(l.lineNo(), l.quantity(), Integer::sum));
            return new RefundOrderCommand(device.terminalId(), idempotencyKey, new OrderId(id), units,
                    request.restock(), request.method(), request.reason());
        });
        RefundOrderUseCase.RefundResult result = timed("refund", () -> refundOrder.refund(command), r -> r.replayed());
        return respond(result.replayed(), HttpStatus.CREATED, new RefundResponseBody(
                OrderResponse.RefundResponse.from(result.refund()), OrderResponse.from(result.account())));
    }

    private static <T> ResponseEntity<T> respond(boolean replayed, HttpStatus firstStatus, T body) {
        return ResponseEntity.status(replayed ? HttpStatus.OK : firstStatus)
                .header(REPLAYED, Boolean.toString(replayed))
                .body(body);
    }

    /**
     * Latency and outcome per operation ({@code storefront.orders.operation}): created / replayed / the
     * domain error code / busy / error — the basis for the p99 SLO and the oversell-attempt rate.
     */
    private <T> T timed(String operation, Supplier<T> call, Predicate<T> replayed) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        try {
            T result = call.get();
            outcome = replayed.test(result) ? "replayed" : "created";
            return result;
        } catch (DomainException e) {
            outcome = e.code();
            throw e;
        } catch (TransientDataAccessException | CannotGetJdbcConnectionException e) {
            outcome = "BUSY_RETRY";
            throw e;
        } finally {
            sample.stop(Timer.builder("storefront.orders.operation")
                    .description("Order operations by type and outcome")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meters));
        }
    }

    record PaymentRequest(@NotNull PaymentMethod method, @NotNull @DecimalMin("0") BigDecimal amount,
                          @DecimalMin("0") BigDecimal tendered, @Size(min = 1, max = 64) String reference) {
    }

    record PaymentResponseBody(OrderResponse.PaymentResponse payment, OrderResponse order) {
    }

    record CancelRequest(@NotBlank @Size(max = 200) String reason) {
    }

    record RefundRequest(@NotEmpty @Size(max = 100) List<@Valid RefundLineRequest> lines, boolean restock,
                         @NotNull PaymentMethod method, @NotBlank @Size(max = 200) String reason) {
    }

    record RefundLineRequest(@Min(1) int lineNo, @Min(1) @Max(Quantity.MAX_PER_LINE) int quantity) {
    }

    record RefundResponseBody(OrderResponse.RefundResponse refund, OrderResponse order) {
    }
}
