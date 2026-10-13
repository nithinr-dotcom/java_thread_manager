package com.fleet.storefront.application.port.in;

import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.shared.DomainException;

import java.util.Collection;

public final class UnknownProductException extends DomainException {

    public UnknownProductException(Collection<Sku> skus) {
        super("UNKNOWN_PRODUCT", "Unknown product(s): " + skus);
    }
}
