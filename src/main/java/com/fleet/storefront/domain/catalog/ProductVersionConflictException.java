package com.fleet.storefront.domain.catalog;

import com.fleet.storefront.domain.shared.DomainException;

import java.util.Map;

/** Optimistic concurrency: the product changed since the caller read it. */
public final class ProductVersionConflictException extends DomainException {

    private final long currentVersion;

    public ProductVersionConflictException(Sku sku, long currentVersion) {
        super("VERSION_CONFLICT", "Product " + sku + " was changed concurrently (current version " + currentVersion + ")");
        this.currentVersion = currentVersion;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("currentVersion", currentVersion);
    }
}
