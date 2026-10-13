package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.catalog.Product;

import java.util.List;
import java.util.Objects;

public interface BrowseCatalogQuery {

    /** Products with their current stock, in SKU order; inactive ones only if requested. */
    List<CatalogItem> catalog(boolean includeInactive);

    record CatalogItem(Product product, int onHand) {
        public CatalogItem {
            Objects.requireNonNull(product, "product");
        }
    }
}
