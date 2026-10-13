package com.fleet.storefront.application.port.in;

import com.fleet.storefront.application.port.in.BrowseCatalogQuery.CatalogItem;
import com.fleet.storefront.domain.catalog.Sku;

import java.math.BigDecimal;
import java.util.Objects;

/** Back-office catalog maintenance. Price changes never alter issued orders (prices are snapshotted). */
public interface ManageCatalogUseCase {

    CatalogItem create(ProductDefinition definition);

    /** Optimistic: fails with {@code VERSION_CONFLICT} unless {@code expectedVersion} is still current. */
    CatalogItem update(ProductDefinition definition, boolean active, long expectedVersion);

    record ProductDefinition(Sku sku, String name, BigDecimal unitPrice, BigDecimal taxRatePercent,
                             boolean requiresPreparation, String station, String category) {
        public ProductDefinition {
            Objects.requireNonNull(sku, "sku");
            Objects.requireNonNull(unitPrice, "unitPrice");
            Objects.requireNonNull(taxRatePercent, "taxRatePercent");
            if (name == null || name.isBlank() || name.length() > 200) {
                throw new IllegalArgumentException("Name must be 1..200 characters");
            }
            if (unitPrice.signum() < 0) {
                throw new IllegalArgumentException("Unit price must not be negative");
            }
        }
    }
}
