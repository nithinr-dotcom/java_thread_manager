package com.fleet.storefront.domain.inventory;

import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.shared.DomainException;

import java.util.Map;

public final class InsufficientStockException extends DomainException {

    private final Sku sku;
    private final int requested;

    public InsufficientStockException(Sku sku, int requested) {
        super("OUT_OF_STOCK", "Insufficient stock for " + sku + " (requested " + requested + ")");
        this.sku = sku;
        this.requested = requested;
    }

    public Sku sku() {
        return sku;
    }

    public int requested() {
        return requested;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("sku", sku.value(), "requested", requested);
    }
}
