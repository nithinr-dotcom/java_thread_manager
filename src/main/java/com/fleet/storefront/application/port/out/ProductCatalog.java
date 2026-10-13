package com.fleet.storefront.application.port.out;

import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.Sku;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ProductCatalog {

    /** Returns the known products (active or not) among {@code skus}; unknown SKUs are absent from the map. */
    Map<Sku, Product> findBySkus(Collection<Sku> skus);

    Optional<Product> findBySku(Sku sku);

    /** Products in SKU order; inactive ones only if {@code includeInactive}. */
    List<Product> findAll(boolean includeInactive);

    void insert(Product product);

    /** Optimistic update: applies only if the stored version is still {@code expectedVersion}. */
    boolean update(Product product, long expectedVersion);

    /**
     * Inbound sync, last-writer-wins: inserts the product, or overwrites it only if its version is
     * greater than the stored one. Returns whether it was applied.
     */
    boolean upsertIfNewer(Product product);

    /** High-water mark of the HQ catalog feed already applied (0 if never synced). */
    long hqCatalogWatermark();

    void saveHqCatalogWatermark(long version);
}
