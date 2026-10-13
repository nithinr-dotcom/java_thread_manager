package com.fleet.storefront.domain.inventory;

import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.order.Quantity;

import java.util.Objects;

/** Total quantity of one product an order needs to take out of stock. */
public record StockRequirement(ProductId productId, Sku sku, Quantity quantity) {

    public StockRequirement {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(quantity, "quantity");
    }
}
