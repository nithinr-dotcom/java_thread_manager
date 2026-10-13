package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.PlaceOrderCommand;
import com.fleet.storefront.application.readmodel.FiscalChainReport;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderLine;
import com.fleet.storefront.domain.order.Quantity;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.shared.UuidV7;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReportServiceTest {

    private final InMemoryStorefront store = new InMemoryStorefront();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC);
    private final BranchContext branch = new BranchContext("HOSP01", Currency.getInstance("USD"), ZoneId.of("UTC"));
    private final PlaceOrderService checkout = new PlaceOrderService(store, store, store, store, store, store, store,
            branch, clock);
    private final ReportService reports = new ReportService(null, store, store, branch, clock);

    ReportServiceTest() {
        store.add(new Product(new ProductId(UuidV7.next()), Sku.of("TEA"), "Tea", Money.of("2.50", "USD"),
                TaxRate.of("10"), false), 100);
    }

    @Test
    void anIntactDayVerifies_andChainsEachOrderToThePreviousOne() {
        Order first = place(1);
        Order second = place(2);
        place(3);

        FiscalChainReport report = reports.verifyFiscalChain(Optional.of(LocalDate.of(2026, 10, 13)));

        assertThat(report.series()).containsExactly(new FiscalChainReport.Series("ORD", 3, true, null),
                new FiscalChainReport.Series("REF", 0, true, null));
        assertThat(second.fiscalHash()).isNotEqualTo(first.fiscalHash());
    }

    @Test
    void aTamperedDocumentBreaksTheChainFromThatNumberOn() {
        place(1);
        Order victim = place(2);
        place(3);
        // Rewrite history: same number and hash, different content (an extra unit of tea).
        Order forged = Order.restore(victim.id(), victim.number(), victim.terminalId(), victim.currency(),
                List.of(new OrderLine(1, victim.lines().getFirst().productId(),
                        Sku.of("TEA"), "Tea", Money.of("2.50", "USD"), Quantity.of(3), TaxRate.of("10"), false)),
                victim.status(), victim.placedAt(), victim.fiscalHash());
        store.state.orders().put(victim.id(), forged);

        FiscalChainReport.Series ord = reports.verifyFiscalChain(Optional.empty()).series().getFirst();
        assertThat(ord.valid()).isFalse();
        assertThat(ord.firstBrokenNumber()).isEqualTo(victim.number().value());
    }

    @Test
    void deletingTheLastDocumentsOrStrippingHashesIsDetected() {
        place(1);
        Order second = place(2);
        Order third = place(3);

        store.state.orders().remove(third.id());   // truncate the tail
        FiscalChainReport.Series truncated = reports.verifyFiscalChain(Optional.empty()).series().getFirst();
        assertThat(truncated.valid()).isFalse();
        assertThat(truncated.firstBrokenNumber()).isEqualTo(third.number().value());

        store.state.orders().put(third.id(), third);
        store.state.orders().put(second.id(), Order.restore(second.id(), second.number(), second.terminalId(),
                second.currency(), second.lines(), second.status(), second.placedAt(), null));   // strip a hash
        FiscalChainReport.Series stripped = reports.verifyFiscalChain(Optional.empty()).series().getFirst();
        assertThat(stripped.valid()).isFalse();
        assertThat(stripped.firstBrokenNumber()).isEqualTo(second.number().value());
    }

    private Order place(int quantity) {
        return checkout.placeOrder(new PlaceOrderCommand(new TerminalId("pos-1"), UuidV7.next().toString(),
                List.of(new PlaceOrderCommand.Item(Sku.of("TEA"), Quantity.of(quantity))))).order();
    }
}
