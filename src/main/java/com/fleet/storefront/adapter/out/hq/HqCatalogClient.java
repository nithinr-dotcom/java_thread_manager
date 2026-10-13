package com.fleet.storefront.adapter.out.hq;

import com.fleet.storefront.application.port.out.HqCatalogFeed;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** Pulls catalog changes from HQ: {@code GET /api/v1/sync/catalog?branch=…&sinceVersion=…}. */
@Component
@ConditionalOnExpression("!'${storefront.hq.base-url:}'.isBlank()")
class HqCatalogClient implements HqCatalogFeed {

    private static final Logger log = LoggerFactory.getLogger(HqCatalogClient.class);

    private final HqHttpClient hq;

    HqCatalogClient(HqHttpClient hq) {
        this.hq = hq;
    }

    @Override
    public List<Product> changesSince(String branchCode, long sinceVersion) {
        CatalogFeed feed = hq.http().get()
                .uri(u -> u.path("/api/v1/sync/catalog").queryParam("branch", branchCode)
                        .queryParam("sinceVersion", sinceVersion).build())
                .retrieve()
                .body(CatalogFeed.class);
        if (feed == null || feed.products() == null) {
            return List.of();
        }
        List<Product> products = new ArrayList<>();
        for (CatalogFeed.Item item : feed.products()) {
            try {
                products.add(item.toProduct());
            } catch (RuntimeException e) {
                // One malformed item must not block the whole feed forever; it is reported, not applied.
                log.warn("Skipping malformed HQ catalog item {} ({}): {}", item.productId(), item.sku(), e.toString());
            }
        }
        products.sort(Comparator.comparingLong(Product::version));
        return products;
    }

    record CatalogFeed(List<Item> products) {

        record Item(UUID productId, String sku, String name, BigDecimal unitPrice, String currency,
                    BigDecimal taxRatePercent, boolean requiresPreparation, String station, String category,
                    boolean active, long version) {

            Product toProduct() {
                return new Product(new ProductId(productId), Sku.of(sku), name,
                        Money.of(unitPrice, Currency.getInstance(currency)), new TaxRate(taxRatePercent),
                        requiresPreparation, station, category, active, version);
            }
        }
    }
}
