package com.fleet.storefront.domain.catalog;

import com.fleet.storefront.domain.shared.DomainException;

import java.util.Collection;

public final class ProductInactiveException extends DomainException {

    public ProductInactiveException(Collection<Sku> skus) {
        super("PRODUCT_INACTIVE", "Product(s) no longer sold: " + skus);
    }
}
