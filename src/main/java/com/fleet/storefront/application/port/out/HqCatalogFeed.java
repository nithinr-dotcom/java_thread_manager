package com.fleet.storefront.application.port.out;

import com.fleet.storefront.domain.catalog.Product;

import java.util.List;

/** Central HQ's catalog change feed (pull). Implementations return an empty list while offline. */
public interface HqCatalogFeed {

    /** Products changed at HQ with a version greater than {@code sinceVersion}, in version order. */
    List<Product> changesSince(String branchCode, long sinceVersion);
}
