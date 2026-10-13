package com.fleet.storefront.domain;

import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.fiscal.FiscalChain;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderDraft;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.OrderLine;
import com.fleet.storefront.domain.order.OrderNumber;
import com.fleet.storefront.domain.order.OrderStatus;
import com.fleet.storefront.domain.order.Quantity;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.shared.UuidV7;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiscalChainTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void firstDocumentLinksToGenesis_andEachHashDependsOnThePreviousOne() {
        String a = FiscalChain.link(null, "doc-1");
        assertThat(a).isEqualTo(FiscalChain.link(FiscalChain.GENESIS, "doc-1")).hasSize(64);
        assertThat(FiscalChain.link(a, "doc-2")).isNotEqualTo(FiscalChain.link(FiscalChain.GENESIS, "doc-2"));
        assertThatThrownBy(() -> FiscalChain.link("nope", "doc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderHashCoversItsFiscalContentButNotItsStatus() {
        Order order = order(2, null);
        Order restored = Order.restore(order.id(), order.number(), order.terminalId(), USD, order.lines(),
                OrderStatus.PAID, order.placedAt(), order.fiscalHash());

        assertThat(FiscalChain.link(null, restored.canonicalForm())).isEqualTo(order.fiscalHash());
        assertThat(order(3, null).fiscalHash()).isNotEqualTo(order.fiscalHash());
        assertThat(order(2, order.fiscalHash()).fiscalHash()).isNotEqualTo(order.fiscalHash());
    }

    private static Order order(int quantity, String previousHash) {
        Product p = new Product(new ProductId(UuidV7.next()), Sku.of("TEA"), "Tea", Money.of("2.50", "USD"),
                TaxRate.of("10"), false);
        return new OrderDraft(USD, List.of(OrderLine.of(1, p, Quantity.of(quantity)))).issue(
                new OrderId(UuidV7.next()), new OrderNumber("HOSP01", LocalDate.of(2026, 10, 13), 1),
                new TerminalId("pos-1"), Instant.parse("2026-10-13T12:00:00.123456Z"), previousHash);
    }
}
