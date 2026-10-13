package com.fleet.storefront.domain;

import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.inventory.StockRequirement;
import com.fleet.storefront.domain.money.CurrencyMismatchException;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderDraft;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.OrderLine;
import com.fleet.storefront.domain.order.OrderNumber;
import com.fleet.storefront.domain.order.Quantity;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.order.event.KitchenTicketRequested;
import com.fleet.storefront.domain.shared.UuidV7;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Currency USD = Currency.getInstance("USD");

    private final Product mealBox = product("MEALBOX", "8.95", "USD", "8.25", false);
    private final Product burger = product("BURGER", "11.40", "USD", "8.25", true);

    @Test
    void computesLineAndOrderTotalsExactly() {
        OrderDraft draft = new OrderDraft(USD, List.of(
                OrderLine.of(1, mealBox, Quantity.of(3)),   // net 26.85, tax 2.215125 -> 2.22
                OrderLine.of(2, burger, Quantity.of(1))));  // net 11.40, tax 0.9405   -> 0.94

        assertThat(draft.subtotal()).isEqualTo(Money.of("38.25", "USD"));
        assertThat(draft.taxTotal()).isEqualTo(Money.of("3.16", "USD"));
        assertThat(draft.total()).isEqualTo(Money.of("41.41", "USD"));
    }

    @Test
    void rejectsLinesPricedInAnotherCurrency() {
        Product euroItem = product("CROISSANT", "2.10", "EUR", "7", false);
        assertThatThrownBy(() -> new OrderDraft(USD, List.of(
                OrderLine.of(1, mealBox, Quantity.of(1)),
                OrderLine.of(2, euroItem, Quantity.of(1)))))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void rejectsEmptyOrdersAndDuplicateProducts() {
        assertThatThrownBy(() -> new OrderDraft(USD, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderDraft(USD, List.of(
                OrderLine.of(1, mealBox, Quantity.of(1)),
                OrderLine.of(2, mealBox, Quantity.of(1)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantitiesMustBePositiveAndBounded() {
        assertThatThrownBy(() -> Quantity.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Quantity.of(1000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stockRequirementsAreSortedByProductIdForDeterministicLocking() {
        OrderDraft draft = new OrderDraft(USD, List.of(
                OrderLine.of(1, burger, Quantity.of(2)),
                OrderLine.of(2, mealBox, Quantity.of(1))));

        assertThat(draft.stockRequirements())
                .extracting(StockRequirement::productId)
                .isSorted();
    }

    @Test
    void kitchenTicketContainsOnlyItemsThatNeedPreparation() {
        Order order = issue(new OrderDraft(USD, List.of(
                OrderLine.of(1, mealBox, Quantity.of(1)),
                OrderLine.of(2, burger, Quantity.of(2)))));

        KitchenTicketRequested ticket = order.kitchenTicketRequest().orElseThrow();
        assertThat(ticket.orderNumber()).isEqualTo("ORD-HOSP01-20261013-0007");
        assertThat(ticket.items()).containsExactly(new KitchenTicketRequested.Item("BURGER", "BURGER item", 2, "KITCHEN"));
    }

    @Test
    void ordersWithoutPreparedFoodDoNotNotifyTheKitchen() {
        Order order = issue(new OrderDraft(USD, List.of(OrderLine.of(1, mealBox, Quantity.of(1)))));
        assertThat(order.kitchenTicketRequest()).isEmpty();
    }

    @Test
    void restoreRederivesTotalsFromLines() {
        Order original = issue(new OrderDraft(USD, List.of(OrderLine.of(1, burger, Quantity.of(3)))));
        Order restored = Order.restore(original.id(), original.number(), original.terminalId(), USD,
                original.lines(), original.status(), original.placedAt(), original.fiscalHash());
        assertThat(restored.total()).isEqualTo(original.total());
        assertThat(restored).isEqualTo(original);
        assertThat(restored.canonicalForm()).isEqualTo(original.canonicalForm());
    }

    private static Order issue(OrderDraft draft) {
        return draft.issue(OrderId.newId(), new OrderNumber("HOSP01", LocalDate.of(2026, 10, 13), 7),
                new TerminalId("pos-1"), Instant.parse("2026-10-13T12:00:00Z"), null);
    }

    private static Product product(String sku, String price, String currency, String tax, boolean prep) {
        return new Product(new ProductId(UuidV7.next()), Sku.of(sku), sku + " item", Money.of(price, currency),
                TaxRate.of(tax), prep);
    }
}
