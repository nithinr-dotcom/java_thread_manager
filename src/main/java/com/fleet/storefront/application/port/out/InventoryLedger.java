package com.fleet.storefront.application.port.out;

import com.fleet.storefront.application.readmodel.StockMovementView;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.inventory.InsufficientStockException;
import com.fleet.storefront.domain.inventory.StockChange;
import com.fleet.storefront.domain.inventory.StockRequirement;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Stock on hand plus its append-only movement journal. Every change writes exactly one journal row
 * in the same atomic statement as the stock update.
 */
public interface InventoryLedger {

    /**
     * Atomically takes the required quantities out of stock, in the given order.
     *
     * @throws InsufficientStockException if any product lacks stock; the caller's transaction must roll back
     */
    void take(List<StockRequirement> requirements, StockChange change);

    /** Atomically adds {@code quantity} units (restock, cancellation, refund); returns the new stock on hand. */
    int add(ProductId productId, int quantity, StockChange change);

    /**
     * Atomically removes {@code quantity} units without going below zero (waste, damage, count correction).
     *
     * @throws InsufficientStockException if fewer units are on hand
     */
    int remove(ProductId productId, Sku sku, int quantity, StockChange change);

    /** Current stock; products without an inventory record are absent from the map. */
    Map<ProductId, Integer> onHand(Collection<ProductId> productIds);

    /** Newest first. */
    List<StockMovementView> movements(ProductId productId, Sku sku, int limit);
}
