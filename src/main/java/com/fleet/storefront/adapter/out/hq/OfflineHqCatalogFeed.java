package com.fleet.storefront.adapter.out.hq;

import com.fleet.storefront.application.port.out.HqCatalogFeed;
import com.fleet.storefront.domain.catalog.Product;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

/** No HQ configured: the node runs on its local catalog (managed in the back office or pushed by HQ). */
@Component
@ConditionalOnExpression("'${storefront.hq.base-url:}'.isBlank()")
class OfflineHqCatalogFeed implements HqCatalogFeed {

    @Override
    public List<Product> changesSince(String branchCode, long sinceVersion) {
        return List.of();
    }
}
