package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.IdempotencyKeyReusedException;
import com.fleet.storefront.application.port.in.Limit;
import com.fleet.storefront.application.port.in.RestockUseCase;
import com.fleet.storefront.application.port.in.StockMovementsQuery;
import com.fleet.storefront.application.port.in.UnknownProductException;
import com.fleet.storefront.application.port.out.IdempotencyStore;
import com.fleet.storefront.application.port.out.InventoryLedger;
import com.fleet.storefront.application.port.out.ProductCatalog;
import com.fleet.storefront.application.port.out.TransactionRunner;
import com.fleet.storefront.application.readmodel.StockMovementView;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.inventory.StockChange;
import com.fleet.storefront.domain.inventory.StockChangeReason;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.shared.Sha256;
import com.fleet.storefront.domain.shared.UuidV7;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Restocks and manual adjustments. Locks: idempotency key → one inventory row (a single atomic statement
 * plus its journal row), so they cannot join a lock cycle with checkouts. The idempotency claim makes a
 * retried request (lost response) a no-op instead of phantom stock.
 */
public final class InventoryService implements RestockUseCase, StockMovementsQuery {

    private final ProductCatalog catalog;
    private final InventoryLedger inventory;
    private final IdempotencyStore idempotency;
    private final TransactionRunner transactions;
    private final Clock clock;

    public InventoryService(ProductCatalog catalog, InventoryLedger inventory, IdempotencyStore idempotency,
                            TransactionRunner transactions, Clock clock) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.idempotency = idempotency;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public StockLevel restock(RestockCommand command) {
        Product product = product(command.sku());
        String fingerprint = Sha256.hex("restock:" + product.id().value() + ":" + command.quantity() + ":"
                + (command.note() == null ? "" : command.note()));
        return once(command.actor(), command.idempotencyKey(), fingerprint, product, operationId -> inventory.add(
                product.id(), command.quantity(),
                new StockChange(StockChangeReason.RESTOCK, operationId, command.actor().value(), command.note())));
    }

    @Override
    public StockLevel adjust(AdjustStockCommand command) {
        Product product = product(command.sku());
        String fingerprint = Sha256.hex("adjust:" + product.id().value() + ":" + command.delta() + ":"
                + command.reason() + ":" + (command.note() == null ? "" : command.note()));
        return once(command.actor(), command.idempotencyKey(), fingerprint, product, operationId -> {
            StockChange change = new StockChange(command.reason(), operationId, command.actor().value(), command.note());
            return command.delta() > 0
                    ? inventory.add(product.id(), command.delta(), change)
                    : inventory.remove(product.id(), product.sku(), -command.delta(), change);
        });
    }

    private StockLevel once(TerminalId actor, String key, String fingerprint, Product product,
                            Function<UUID, Integer> change) {
        return transactions.inTransaction(() -> {
            IdempotencyStore.Claim claim = idempotency.claim(actor, key, fingerprint, clock.instant());
            if (claim instanceof IdempotencyStore.AlreadyCompleted done) {
                if (!done.requestFingerprint().equals(fingerprint)) {
                    throw new IdempotencyKeyReusedException(key);
                }
                int onHand = inventory.onHand(List.of(product.id())).getOrDefault(product.id(), 0);
                return new StockLevel(product.sku(), onHand, true);
            }
            UUID operationId = UuidV7.next();
            int onHand = change.apply(operationId);
            idempotency.complete(actor, key, operationId);
            return new StockLevel(product.sku(), onHand, false);
        });
    }

    @Override
    public List<StockMovementView> movements(Sku sku, Limit limit) {
        Product product = product(sku);
        return inventory.movements(product.id(), product.sku(), limit.value());
    }

    private Product product(Sku sku) {
        return catalog.findBySku(sku).orElseThrow(() -> new UnknownProductException(List.of(sku)));
    }
}
