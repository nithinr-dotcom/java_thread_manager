package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.CancelOrderUseCase.CancelOrderCommand;
import com.fleet.storefront.application.port.in.CancelOrderUseCase.CancelResult;
import com.fleet.storefront.application.port.in.IdempotencyKeyReusedException;
import com.fleet.storefront.application.port.in.OrderNotFoundException;
import com.fleet.storefront.application.port.in.PayOrderUseCase.PayOrderCommand;
import com.fleet.storefront.application.port.in.PayOrderUseCase.PaymentResult;
import com.fleet.storefront.application.port.in.PlaceOrderCommand;
import com.fleet.storefront.application.port.in.RefundOrderUseCase.RefundOrderCommand;
import com.fleet.storefront.application.port.in.RefundOrderUseCase.RefundResult;
import com.fleet.storefront.application.port.in.VoidPaymentUseCase.VoidPaymentCommand;
import com.fleet.storefront.application.port.in.VoidPaymentUseCase.VoidResult;
import com.fleet.storefront.application.port.out.Outbox;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.fiscal.FiscalSeries;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.OrderNotCancellableException;
import com.fleet.storefront.domain.order.OrderNotPayableException;
import com.fleet.storefront.domain.order.OrderStatus;
import com.fleet.storefront.domain.order.Quantity;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.order.event.KitchenTicketCancelled;
import com.fleet.storefront.domain.order.event.OrderCancelled;
import com.fleet.storefront.domain.order.event.PaymentRecorded;
import com.fleet.storefront.domain.order.event.RefundIssued;
import com.fleet.storefront.domain.payment.PaymentMethod;
import com.fleet.storefront.domain.refund.RefundExceedsSoldException;
import com.fleet.storefront.domain.shared.UuidV7;
import com.fleet.storefront.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pay, cancel and refund use cases: rules, idempotency and the global lock order (CONTRIBUTING.md invariant 2). */
class OrderLifecycleServiceTest {

    private static final TerminalId POS = new TerminalId("pos-1");
    private static final TerminalId MANAGER = new TerminalId("mgr-1");

    private final InMemoryStorefront store = new InMemoryStorefront();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC);
    private final BranchContext branch = new BranchContext("HOSP01", Currency.getInstance("USD"), ZoneId.of("UTC"));
    private final PlaceOrderService checkout = new PlaceOrderService(store, store, store, store, store, store, store,
            branch, clock);
    private final PayOrderService pay = new PayOrderService(store, store, store, store, clock);
    private final CancelOrderService cancel = new CancelOrderService(store, store, store, store, store, clock);
    private final RefundOrderService refund = new RefundOrderService(store, store, store, store, store, store,
            branch, clock);

    private final Product water = product("WATER", "2.50", false);
    private final Product burger = product("BURGER", "10.00", true);

    OrderLifecycleServiceTest() {
        store.add(water, 10);
        store.add(burger, 10);
    }

    // --- Pay ----------------------------------------------------------------------------------

    @Test
    void cashPaymentSettlesTheOrder_withServerComputedChange_andNotifiesHq() {
        Order order = placed(); // 2 × 2.50 + 1 × 10.00, 10 % tax → 16.50
        store.calls.clear();

        PaymentResult result = pay.pay(cash(order, "k-pay", "16.50", "20.00"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.payment().change()).isEqualTo(Money.of("3.50", "USD"));
        assertThat(result.account().status()).isEqualTo(OrderStatus.PAID);
        assertThat(store.calls).containsExactly("claim", "lock-order", "insert-payment", "status:PAID", "complete",
                "outbox:HQ");
        assertThat(lastOutbox().payload()).isInstanceOf(PaymentRecorded.class);
    }

    @Test
    void paymentRetryWithTheSameKeyIsReplayed_andAKeyReusedForAnotherPaymentIsRejected() {
        Order order = placed();
        PaymentResult first = pay.pay(card(order, "k-pay", "5.00"));
        PaymentResult retry = pay.pay(card(order, "k-pay", "5.00"));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.payment().id()).isEqualTo(first.payment().id());
        assertThat(retry.account().payments()).hasSize(1);
        assertThatThrownBy(() -> pay.pay(card(order, "k-pay", "6.00"))).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void amountsWithTooManyDecimalsAreRejected_andUnknownOrdersAreNotFound() {
        Order order = placed();
        assertThatThrownBy(() -> pay.pay(card(order, "k1", "5.001"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> pay.pay(new PayOrderCommand(POS, "k2", new OrderId(UuidV7.next()), PaymentMethod.CARD,
                BigDecimal.ONE, null, "A"))).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void anAbandonedSplitTenderIsVoided_thenTheOrderCanBeCancelled() {
        Order order = placed();
        PaymentResult part = pay.pay(card(order, "k-pay", "10.00"));
        assertThatThrownBy(() -> cancel.cancel(new CancelOrderCommand(POS, "k-c1", order.id(), "Card declined")))
                .isInstanceOf(OrderNotCancellableException.class);
        store.calls.clear();

        VoidResult voided = pay.voidPayment(new VoidPaymentCommand(POS, "k-void", order.id(), part.payment().id(),
                "Second card declined"));

        assertThat(store.calls).containsExactly("claim", "lock-order", "void-payment", "complete", "outbox:HQ");
        assertThat(voided.account().amountPaid()).isEqualTo(Money.of("0.00", "USD"));
        assertThat(pay.voidPayment(new VoidPaymentCommand(POS, "k-void", order.id(), part.payment().id(),
                "Second card declined")).replayed()).isTrue();
        assertThat(cancel.cancel(new CancelOrderCommand(POS, "k-c2", order.id(), "Card declined")).account().status())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void paymentsOfAPaidOrderCannotBeVoided_moneyGoesBackThroughRefunds() {
        Order order = placed();
        PaymentResult full = pay.pay(card(order, "k-pay", "16.50"));
        assertThatThrownBy(() -> pay.voidPayment(new VoidPaymentCommand(POS, "k-void", order.id(),
                full.payment().id(), "oops"))).isInstanceOf(OrderNotPayableException.class);
    }

    // --- Cancel -------------------------------------------------------------------------------

    @Test
    void cancellingReturnsTheStockInProductOrder_cancelsTheKitchenTicket_andKeepsTheNumber() {
        Order order = placed();
        store.calls.clear();

        CancelResult result = cancel.cancel(new CancelOrderCommand(POS, "k-cancel", order.id(), "Customer left"));

        assertThat(result.account().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(store.stockOf(water)).isEqualTo(10);
        assertThat(store.stockOf(burger)).isEqualTo(10);
        assertThat(store.lastIssued()).isEqualTo(1);
        List<String> restockInIdOrder = Stream.of(water, burger).sorted(Comparator.comparing(Product::id))
                .map(p -> "add:" + p.sku()).toList();
        assertThat(store.calls).startsWith("claim", "lock-order").containsSubsequence(restockInIdOrder)
                .endsWith("status:CANCELLED", "complete", "outbox:KITCHEN", "outbox:HQ");
        assertThat(store.state.outbox()).extracting(Outbox.Message::payload)
                .hasAtLeastOneElementOfType(KitchenTicketCancelled.class)
                .hasAtLeastOneElementOfType(OrderCancelled.class);
    }

    @Test
    void cancellationIsIdempotent_andAPaidOrderCannotBeCancelled() {
        Order order = placed();
        cancel.cancel(new CancelOrderCommand(POS, "k-cancel", order.id(), "Customer left"));
        assertThat(cancel.cancel(new CancelOrderCommand(POS, "k-cancel", order.id(), "Customer left")).replayed()).isTrue();
        assertThat(store.stockOf(water)).isEqualTo(10);

        Order paid = placed();
        pay.pay(card(paid, "k-pay", "16.50"));
        assertThatThrownBy(() -> cancel.cancel(new CancelOrderCommand(POS, "k-c2", paid.id(), "x")))
                .isInstanceOf(OrderNotCancellableException.class);
    }

    // --- Refund -------------------------------------------------------------------------------

    @Test
    void refundIssuesARefundDocumentLast_afterRestockingInProductOrder() {
        Order order = placed();
        pay.pay(card(order, "k-pay", "16.50"));
        store.calls.clear();

        RefundResult result = refund.refund(refundOf(order, "k-ref", 2, 1, true)); // line 2 = WATER

        assertThat(result.refund().number().value()).isEqualTo("REF-HOSP01-20261013-0001");
        assertThat(result.refund().total()).isEqualTo(Money.of("2.75", "USD"));
        assertThat(result.account().status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(store.calls).containsExactly("claim", "lock-order", "add:" + lineSku(order, 2),
                "fiscal:REF", "fiscal-hash:REF", "insert-refund", "status:PARTIALLY_REFUNDED", "complete", "outbox:HQ");
        assertThat(lastOutbox().payload()).isInstanceOf(RefundIssued.class);
        assertThat(store.lastIssued(FiscalSeries.REF)).isEqualTo(1);
        assertThat(store.lastIssued(FiscalSeries.ORD)).isEqualTo(1);
    }

    @Test
    void refundWithoutRestockLeavesStock_andAFailedRefundConsumesNoNumber() {
        Order order = placed();
        pay.pay(card(order, "k-pay", "16.50"));
        int before = store.stockOf(burger);

        refund.refund(refundOf(order, "k-ref", 1, 1, false)); // line 1 = the single BURGER
        assertThat(store.stockOf(burger)).isEqualTo(before);

        assertThatThrownBy(() -> refund.refund(refundOf(order, "k-ref2", 1, 1, true)))
                .isInstanceOf(RefundExceedsSoldException.class);
        assertThat(store.lastIssued(FiscalSeries.REF)).isEqualTo(1);
        assertThat(refund.refund(refundOf(order, "k-ref", 1, 1, false)).replayed()).isTrue();
    }

    private Order placed() {
        return checkout.placeOrder(new PlaceOrderCommand(POS, UuidV7.next().toString(), List.of(
                new PlaceOrderCommand.Item(water.sku(), Quantity.of(2)),
                new PlaceOrderCommand.Item(burger.sku(), Quantity.of(1))))).order();
    }

    private static String lineSku(Order order, int lineNo) {
        return order.line(lineNo).orElseThrow().sku().value();
    }

    private Outbox.Message lastOutbox() {
        return store.state.outbox().getLast();
    }

    private static PayOrderCommand cash(Order order, String key, String amount, String tendered) {
        return new PayOrderCommand(POS, key, order.id(), PaymentMethod.CASH, new BigDecimal(amount),
                new BigDecimal(tendered), null);
    }

    private static PayOrderCommand card(Order order, String key, String amount) {
        return new PayOrderCommand(POS, key, order.id(), PaymentMethod.CARD, new BigDecimal(amount), null, "AUTH-1");
    }

    private static RefundOrderCommand refundOf(Order order, String key, int lineNo, int units, boolean restock) {
        TreeMap<Integer, Integer> lines = new TreeMap<>();
        lines.put(lineNo, units);
        return new RefundOrderCommand(MANAGER, key, order.id(), lines, restock, PaymentMethod.CASH, "Cold food");
    }

    private static Product product(String sku, String price, boolean prep) {
        return new Product(new ProductId(UuidV7.next()), Sku.of(sku), sku, Money.of(price, "USD"), TaxRate.of("10"), prep);
    }
}
