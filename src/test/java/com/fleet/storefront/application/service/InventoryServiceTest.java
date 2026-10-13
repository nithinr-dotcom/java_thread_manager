package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.IdempotencyKeyReusedException;
import com.fleet.storefront.application.port.in.Limit;
import com.fleet.storefront.application.port.in.RestockUseCase.AdjustStockCommand;
import com.fleet.storefront.application.port.in.RestockUseCase.RestockCommand;
import com.fleet.storefront.application.port.in.RestockUseCase.StockLevel;
import com.fleet.storefront.application.port.in.UnknownProductException;
import com.fleet.storefront.application.readmodel.StockMovementView;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.inventory.InsufficientStockException;
import com.fleet.storefront.domain.inventory.StockChangeReason;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.shared.UuidV7;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class InventoryServiceTest {

    private static final TerminalId MANAGER = new TerminalId("mgr-1");

    private final InMemoryStorefront store = new InMemoryStorefront();
    private final InventoryService service = new InventoryService(store, store, store, store, Clock.systemUTC());
    private final Product burger = new Product(new ProductId(UuidV7.next()), Sku.of("BURGER"), "Burger",
            Money.of("1.00", "USD"), TaxRate.zero(), false);

    InventoryServiceTest() {
        store.add(burger, 0);
    }

    @Test
    void restockAddsStockAndJournalsIt() {
        assertThat(service.restock(new RestockCommand(Sku.of("burger"), 7, "Delivery 1", MANAGER, "r1")).onHand()).isEqualTo(7);
        assertThat(service.restock(new RestockCommand(Sku.of("BURGER"), 3, null, MANAGER, "r2")).onHand()).isEqualTo(10);

        assertThat(service.movements(Sku.of("BURGER"), new Limit(10)))
                .extracting(StockMovementView::delta, StockMovementView::reason, StockMovementView::onHandAfter)
                .containsExactly(tuple(3, "RESTOCK", 10),
                        tuple(7, "RESTOCK", 7));
    }

    @Test
    void aRetriedRestockIsReplayed_neverAddingTheUnitsTwice() {
        assertThat(service.restock(new RestockCommand(Sku.of("BURGER"), 50, null, MANAGER, "r1")).replayed()).isFalse();
        StockLevel retry = service.restock(new RestockCommand(Sku.of("BURGER"), 50, null, MANAGER, "r1"));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.onHand()).isEqualTo(50);
        assertThatThrownBy(() -> service.restock(new RestockCommand(Sku.of("BURGER"), 60, null, MANAGER, "r1")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void wasteNeverTakesStockBelowZero() {
        service.restock(new RestockCommand(Sku.of("BURGER"), 2, null, MANAGER, "r1"));

        assertThat(service.adjust(new AdjustStockCommand(Sku.of("BURGER"), -2, StockChangeReason.WASTE, "Expired",
                MANAGER, "a1")).onHand()).isZero();
        assertThatThrownBy(() -> service.adjust(new AdjustStockCommand(Sku.of("BURGER"), -1, StockChangeReason.WASTE,
                null, MANAGER, "a2"))).isInstanceOf(InsufficientStockException.class);
        assertThat(service.adjust(new AdjustStockCommand(Sku.of("BURGER"), 4, StockChangeReason.COUNT_CORRECTION,
                null, MANAGER, "a3")).onHand()).isEqualTo(4);
    }

    @Test
    void commandsValidateTheirShape_andUnknownProductsAreRejectedWithoutTouchingStock() {
        assertThatThrownBy(() -> new RestockCommand(Sku.of("BURGER"), 0, null, MANAGER, "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdjustStockCommand(Sku.of("BURGER"), 1, StockChangeReason.SALE, null, MANAGER, "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.restock(new RestockCommand(Sku.of("NOPE"), 1, null, MANAGER, "k")))
                .isInstanceOf(UnknownProductException.class);
        assertThat(store.calls).isEmpty();
    }
}
