package com.fleet.storefront.domain.catalog;

import com.fleet.storefront.domain.shared.DomainException;

public final class ProductExistsException extends DomainException {

    public ProductExistsException(Sku sku) {
        super("PRODUCT_EXISTS", "A product with SKU " + sku + " already exists");
    }
}
