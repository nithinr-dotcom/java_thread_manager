package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.IdempotencyKeyReusedException;
import com.fleet.storefront.application.port.in.PlaceOrderCommand;
import com.fleet.storefront.application.port.in.PlaceOrderResult;
import com.fleet.storefront.application.port.in.UnknownProductException;
import com.fleet.storefront.application.port.out.Outbox;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.inventory.InsufficientStockException;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import com.fleet.storefront.domain.order.Quantity;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.shared.UuidV7;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceOrderServiceTest {

    private final InMemoryStorefront store = new InMemoryStorefront();
    private final PlaceOrderService service = new PlaceOrderService(store, store, store, store, store, store, store,
            new BranchContext("HOSP01", Currency.getInstance("USD"), ZoneId.of("UTC")),
            Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC));

    private final Product water = product("WATER", false);
    private final Product burger = product("BURGER", true);

    PlaceOrderServiceTest() {
        store.add(water, 5);
        store.add(burger, 5);
    }

    @Test
    void placesAnOrderTakingStockAndIssuingTheFirstNumberOfTheDay() {
        PlaceOrderResult result = service.placeOrder(command("pos-1", "k1", item("WATER", 2), item("BURGER", 1)));

        assertThat(result.replayed()).isFalse();
        assertThat(result.order().number().value()).isEqualTo("ORD-HOSP01-20261013-0001");
        assertThat(store.stockOf(water)).isEqualTo(3);
        assertThat(store.stockOf(burger)).isEqualTo(4);
        assertThat(store.state.outbox()).extracting(Outbox.Message::destination)
                .containsExactly(Outbox.Destination.KITCHEN, Outbox.Destination.HQ);
    }

    /** CONTRIBUTING.md invariants 2–5: idempotency key → stock (by product id) → fiscal counter → writes → outbox. */
    @Test
    void acquiresLocksInTheFixedGlobalOrder() {
        // Basket given in reverse SKU order; stock must still be taken in product-id order.
        List<String> byProductId = Stream.of(water, burger)
                .sorted(Comparator.comparing(Product::id))
                .map(p -> "take:" + p.sku())
                .toList();

        service.placeOrder(command("pos-1", "k1", item("WATER", 1), item("BURGER", 1)));

        List<String> expected = new ArrayList<>();
        expected.add("claim");
        expected.addAll(byProductId);
        expected.addAll(List.of("fiscal:ORD", "fiscal-hash:ORD", "insert", "complete", "outbox:KITCHEN", "outbox:HQ"));
        assertThat(store.calls).containsExactlyElementsOf(expected);
    }

    @Test
    void replayTakesOnlyTheIdempotencyLock() {
        service.placeOrder(command("pos-1", "k1", item("WATER", 1)));
        store.calls.clear();

        service.placeOrder(command("pos-1", "k1", item("WATER", 1)));

        assertThat(store.calls).containsExactly("claim");
    }

    @Test
    void retryWithSameKeyReturnsOriginalOrderWithoutSideEffects() {
        PlaceOrderResult first = service.placeOrder(command("pos-1", "k1", item("WATER", 1)));
        PlaceOrderResult retry = service.placeOrder(command("pos-1", "k1", item("WATER", 1)));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.order().id()).isEqualTo(first.order().id());
        assertThat(store.stockOf(water)).isEqualTo(4);
        assertThat(store.lastIssued()).isEqualTo(1);
        assertThat(store.state.outbox()).hasSize(1);
    }

    @Test
    void sameKeyFromAnotherTerminalIsADifferentRequest() {
        service.placeOrder(command("pos-1", "k1", item("WATER", 1)));
        PlaceOrderResult other = service.placeOrder(command("pos-2", "k1", item("WATER", 1)));
        assertThat(other.replayed()).isFalse();
        assertThat(store.lastIssued()).isEqualTo(2);
    }

    @Test
    void reusingAKeyForADifferentBasketIsRejected() {
        service.placeOrder(command("pos-1", "k1", item("WATER", 1)));
        assertThatThrownBy(() -> service.placeOrder(command("pos-1", "k1", item("WATER", 2))))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void fingerprintIgnoresItemOrderAndSplitLines() {
        assertThat(command("t", "k", item("WATER", 2), item("BURGER", 1)).fingerprint())
                .isEqualTo(command("t", "k", item("BURGER", 1), item("WATER", 1), item("WATER", 1)).fingerprint());
    }

    @Test
    void failedCheckoutConsumesNeitherStockNorAFiscalNumber() {
        assertThatThrownBy(() -> service.placeOrder(command("pos-1", "k1", item("WATER", 1), item("BURGER", 6))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(store.stockOf(water)).isEqualTo(5);
        assertThat(store.lastIssued()).isZero();

        // The key was released by the rollback, so a corrected retry is processed normally.
        PlaceOrderResult ok = service.placeOrder(command("pos-1", "k2", item("BURGER", 5)));
        assertThat(ok.order().number().sequence()).isEqualTo(1);
    }

    @Test
    void unknownProductsAreRejectedBeforeTouchingStock() {
        assertThatThrownBy(() -> service.placeOrder(command("pos-1", "k1", item("WATER", 1), item("NOPE", 1))))
                .isInstanceOf(UnknownProductException.class);
        assertThat(store.stockOf(water)).isEqualTo(5);
    }

    private static PlaceOrderCommand.Item item(String sku, int qty) {
        return new PlaceOrderCommand.Item(Sku.of(sku), Quantity.of(qty));
    }

    private static PlaceOrderCommand command(String terminal, String key, PlaceOrderCommand.Item... items) {
        return new PlaceOrderCommand(new TerminalId(terminal), key, List.of(items));
    }

    private static Product product(String sku, boolean prep) {
        return new Product(new ProductId(UuidV7.next()), Sku.of(sku), sku, Money.of("2.50", "USD"), TaxRate.of("10"), prep);
    }
}
