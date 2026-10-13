package com.fleet.storefront.domain;

import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.money.CurrencyMismatchException;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.order.OrderDraft;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.OrderLine;
import com.fleet.storefront.domain.order.OrderNotCancellableException;
import com.fleet.storefront.domain.order.OrderNotPayableException;
import com.fleet.storefront.domain.order.OrderNotRefundableException;
import com.fleet.storefront.domain.order.OrderNumber;
import com.fleet.storefront.domain.order.OrderStatus;
import com.fleet.storefront.domain.order.Quantity;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.payment.InsufficientTenderException;
import com.fleet.storefront.domain.payment.Payment;
import com.fleet.storefront.domain.payment.PaymentExceedsDueException;
import com.fleet.storefront.domain.payment.PaymentId;
import com.fleet.storefront.domain.payment.PaymentMethod;
import com.fleet.storefront.domain.refund.Refund;
import com.fleet.storefront.domain.refund.RefundDraft;
import com.fleet.storefront.domain.refund.RefundExceedsSoldException;
import com.fleet.storefront.domain.refund.RefundId;
import com.fleet.storefront.domain.refund.RefundNumber;
import com.fleet.storefront.domain.refund.UnknownOrderLineException;
import com.fleet.storefront.domain.shared.UuidV7;
import com.fleet.storefront.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderAccountTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final TerminalId POS = new TerminalId("pos-1");
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    // 2 × 11.40 @ 8.25 % → net 22.80, tax 1.88, total 24.68
    private final OrderAccount account = OrderAccount.of(order(line(1, "BURGER", "11.40", "8.25", 2)));

    @Test
    void cashPaymentComputesChange_andCoveringTheTotalMakesTheOrderPaid() {
        Payment cash = account.pay(PaymentId.newId(), PaymentMethod.CASH, usd("24.68"), usd("30.00"), null, POS, NOW);

        assertThat(cash.change()).isEqualTo(usd("5.32"));
        OrderAccount paid = account.withPayment(cash);
        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.amountPaid()).isEqualTo(usd("24.68"));
        assertThat(paid.amountDue()).isEqualTo(usd("0.00"));
    }

    @Test
    void splitTenderStaysPlacedUntilCovered() {
        Payment card = account.pay(PaymentId.newId(), PaymentMethod.CARD, usd("10.00"), null, "AUTH-1", POS, NOW);
        OrderAccount partly = account.withPayment(card);
        assertThat(partly.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(partly.amountDue()).isEqualTo(usd("14.68"));

        Payment rest = partly.pay(PaymentId.newId(), PaymentMethod.CASH, usd("14.68"), usd("14.68"), null, POS, NOW);
        assertThat(partly.withPayment(rest).status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void paymentsCannotOverpay_underTender_mixCurrencies_orPayASettledOrder() {
        assertThatThrownBy(() -> account.pay(PaymentId.newId(), PaymentMethod.CARD, usd("24.69"), null, "A", POS, NOW))
                .isInstanceOf(PaymentExceedsDueException.class);
        assertThatThrownBy(() -> account.pay(PaymentId.newId(), PaymentMethod.CASH, usd("24.68"), usd("20.00"), null, POS, NOW))
                .isInstanceOf(InsufficientTenderException.class);
        assertThatThrownBy(() -> account.pay(PaymentId.newId(), PaymentMethod.CARD, Money.of("24.68", "EUR"), null, "A", POS, NOW))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> account.pay(PaymentId.newId(), PaymentMethod.CARD, usd("0.00"), null, "A", POS, NOW))
                .isInstanceOf(ValidationException.class);

        OrderAccount paid = account.withPayment(
                account.pay(PaymentId.newId(), PaymentMethod.CARD, usd("24.68"), null, "A", POS, NOW));
        assertThatThrownBy(() -> paid.pay(PaymentId.newId(), PaymentMethod.CARD, usd("1.00"), null, "A", POS, NOW))
                .isInstanceOf(OrderNotPayableException.class);
    }

    @Test
    void onlyAnUnpaidPlacedOrderCanBeCancelled() {
        assertThat(account.cancel().status()).isEqualTo(OrderStatus.CANCELLED);

        OrderAccount partlyPaid = account.withPayment(
                account.pay(PaymentId.newId(), PaymentMethod.CARD, usd("1.00"), null, "A", POS, NOW));
        assertThatThrownBy(partlyPaid::cancel).isInstanceOf(OrderNotCancellableException.class);
        assertThatThrownBy(() -> account.cancel().cancel()).isInstanceOf(OrderNotCancellableException.class);
    }

    @Test
    void onlyPaidOrdersCanBeRefunded_withinTheSoldQuantity() {
        assertThatThrownBy(() -> account.draftRefund(units(1, 1))).isInstanceOf(OrderNotRefundableException.class);

        OrderAccount paid = paid(account);
        assertThatThrownBy(() -> paid.draftRefund(units(1, 3))).isInstanceOf(RefundExceedsSoldException.class);
        assertThatThrownBy(() -> paid.draftRefund(units(9, 1))).isInstanceOf(UnknownOrderLineException.class);
    }

    @Test
    void partialThenFinalRefundReturnsExactlyTheOrderTotal() {
        OrderAccount paid = paid(account);
        OrderAccount once = refund(paid, units(1, 1));
        assertThat(once.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(once.refundedQuantity(1)).isEqualTo(1);

        OrderAccount twice = refund(once, units(1, 1));
        assertThat(twice.status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(twice.amountRefunded()).isEqualTo(account.order().total());
        assertThatThrownBy(() -> twice.draftRefund(units(1, 1))).isInstanceOf(OrderNotRefundableException.class);
    }

    /** Successive one-unit refunds never round past the line tax and always end exactly at the line total. */
    @ParameterizedTest
    @CsvSource({"0.03, 50, 6", "0.05, 50, 3", "11.40, 8.25, 7", "0.01, 7.5, 13", "19.99, 21, 9", "2.35, 16.5, 11"})
    void unitByUnitRefundsSumToTheLineTotal(String price, String rate, int quantity) {
        OrderAccount current = paid(OrderAccount.of(order(line(1, "ITEM", price, rate, quantity))));
        Money previousTax = Money.zero(USD);
        for (int i = 0; i < quantity; i++) {
            RefundDraft draft = current.draftRefund(units(1, 1));
            assertThat(draft.taxTotal().isNegative()).isFalse();
            current = current.withRefund(issue(draft));
            previousTax = previousTax.plus(draft.taxTotal());
        }
        assertThat(current.amountRefunded()).isEqualTo(current.order().total());
        assertThat(previousTax).isEqualTo(current.order().taxTotal());
        assertThat(current.status()).isEqualTo(OrderStatus.REFUNDED);
    }

    private static OrderAccount paid(OrderAccount account) {
        return account.withPayment(account.pay(PaymentId.newId(), PaymentMethod.CARD, account.amountDue(), null,
                "AUTH", POS, NOW));
    }

    private static OrderAccount refund(OrderAccount account, TreeMap<Integer, Integer> units) {
        return account.withRefund(issue(account.draftRefund(units)));
    }

    private static Refund issue(RefundDraft draft) {
        return draft.issue(RefundId.newId(), new RefundNumber("HOSP01", LocalDate.of(2026, 10, 13), 1),
                PaymentMethod.CASH, "test", true, POS, NOW, null);
    }

    private static TreeMap<Integer, Integer> units(int lineNo, int quantity) {
        TreeMap<Integer, Integer> units = new TreeMap<>();
        units.put(lineNo, quantity);
        return units;
    }

    private static Money usd(String amount) {
        return Money.of(amount, "USD");
    }

    private static OrderLine line(int no, String sku, String price, String rate, int qty) {
        Product p = new Product(new ProductId(UuidV7.next()), Sku.of(sku), sku, usd(price), TaxRate.of(rate), true);
        return OrderLine.of(no, p, Quantity.of(qty));
    }

    private static Order order(OrderLine... lines) {
        return new OrderDraft(USD, new ArrayList<>(List.of(lines))).issue(OrderId.newId(),
                new OrderNumber("HOSP01", LocalDate.of(2026, 10, 13), 1), POS, NOW, null);
    }
}
