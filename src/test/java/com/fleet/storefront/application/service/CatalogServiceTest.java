package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.BrowseCatalogQuery.CatalogItem;
import com.fleet.storefront.application.port.in.ManageCatalogUseCase.ProductDefinition;
import com.fleet.storefront.application.port.in.SyncCatalogUseCase.SyncResult;
import com.fleet.storefront.application.port.in.UnknownProductException;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductExistsException;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.ProductVersionConflictException;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.money.CurrencyMismatchException;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import com.fleet.storefront.domain.shared.UuidV7;
import com.fleet.storefront.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    private final InMemoryStorefront store = new InMemoryStorefront();
    private final CatalogService service = new CatalogService(store, store, store, store,
            new BranchContext("HOSP01", Currency.getInstance("USD"), ZoneId.of("UTC")), Clock.fixed(NOW, ZoneOffset.UTC));

    private final Product water = product("WATER", 5);
    private final Product burger = product("BURGER", 5);

    CatalogServiceTest() {
        store.add(water, 5);
        store.add(burger, 0);
    }

    @Test
    void listsActiveProductsWithTheirStockInSkuOrder() {
        store.add(new Product(new ProductId(UuidV7.next()), Sku.of("OLD"), "Old", Money.of("1.00", "USD"),
                TaxRate.zero(), false, null, null, false, 1), 3);

        assertThat(service.catalog(false)).containsExactly(new CatalogItem(burger, 0), new CatalogItem(water, 5));
        assertThat(service.catalog(true)).hasSize(3);
    }

    @Test
    void createsAProductAtTheCurrentTimeVersionWithNoStock() {
        CatalogItem created = service.create(definition("SOUP", "5.90"));

        assertThat(created.onHand()).isZero();
        assertThat(created.product().version()).isEqualTo(NOW.toEpochMilli());
        assertThat(created.product().unitPrice()).isEqualTo(Money.of("5.90", "USD"));
        assertThatThrownBy(() -> service.create(definition("SOUP", "1.00"))).isInstanceOf(ProductExistsException.class);
    }

    @Test
    void rejectsPricesWithMoreDecimalsThanTheBranchCurrency() {
        assertThatThrownBy(() -> service.create(definition("SOUP", "5.901"))).isInstanceOf(ValidationException.class);
    }

    @Test
    void updateIsOptimistic_andBumpsTheVersionToAtLeastNow() {
        CatalogItem updated = service.update(definition("WATER", "3.00"), true, 5);
        assertThat(updated.product().version()).isEqualTo(NOW.toEpochMilli());
        assertThat(updated.product().unitPrice()).isEqualTo(Money.of("3.00", "USD"));
        assertThat(updated.onHand()).isEqualTo(5);

        assertThatThrownBy(() -> service.update(definition("WATER", "4.00"), true, 5))
                .isInstanceOf(ProductVersionConflictException.class);
        assertThatThrownBy(() -> service.update(definition("NOPE", "4.00"), true, 1))
                .isInstanceOf(UnknownProductException.class);
    }

    @Test
    void hqSyncIsLastWriterWinsOnVersion() {
        Product newerWater = new Product(water.id(), water.sku(), "Still water", Money.of("1.80", "USD"),
                TaxRate.zero(), false, null, null, true, 10);
        Product olderBurger = burger.withVersion(1);
        Product brandNew = product("TEA", 7);

        SyncResult result = service.apply(List.of(newerWater, olderBurger, brandNew));

        assertThat(result).isEqualTo(new SyncResult(2, 1));
        assertThat(store.findBySku(Sku.of("WATER")).orElseThrow().name()).isEqualTo("Still water");
        assertThat(store.findBySku(Sku.of("BURGER")).orElseThrow().version()).isEqualTo(5);
        assertThat(store.findBySku(Sku.of("TEA"))).isPresent();
    }

    @Test
    void hqSyncRejectsTheWholeBatchOnAForeignCurrency() {
        Product euro = new Product(new ProductId(UuidV7.next()), Sku.of("EURO"), "Euro", Money.of("1.00", "EUR"),
                TaxRate.zero(), false);
        assertThatThrownBy(() -> service.apply(List.of(product("TEA", 7), euro)))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThat(store.findBySku(Sku.of("TEA"))).isEmpty();
    }

    @Test
    void pullAppliesOnlyChangesAfterTheWatermark_andAdvancesIt() {
        store.hqFeed.add(product("TEA", 7));
        assertThat(service.pullFromHq()).isEqualTo(new SyncResult(1, 0));
        assertThat(store.hqCatalogWatermark()).isEqualTo(7);
        assertThat(service.pullFromHq()).isEqualTo(new SyncResult(0, 0));
    }

    private static ProductDefinition definition(String sku, String price) {
        return new ProductDefinition(Sku.of(sku), sku + " item", new BigDecimal(price), new BigDecimal("8.25"), true,
                "grill", "Mains");
    }

    private static Product product(String sku, long version) {
        return new Product(new ProductId(UuidV7.next()), Sku.of(sku), sku, Money.of("1.00", "USD"), TaxRate.zero(),
                false).withVersion(version);
    }
}
