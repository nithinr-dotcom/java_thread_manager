package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.application.port.in.SyncCatalogUseCase;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** Inbound Central HQ sync (push). */
@RestController
@RequestMapping("/api/v1/sync")
class SyncController {

    private final SyncCatalogUseCase sync;

    SyncController(SyncCatalogUseCase sync) {
        this.sync = sync;
    }

    @PutMapping("/catalog")
    SyncCatalogUseCase.SyncResult catalog(@Valid @RequestBody CatalogSyncRequest request) {
        List<Product> products = Requests.parse(() -> request.products().stream().map(ProductSnapshot::toProduct).toList());
        return sync.apply(products);
    }

    record CatalogSyncRequest(@NotNull @Size(max = 5000) List<@Valid ProductSnapshot> products) {
    }

    record ProductSnapshot(@NotNull UUID productId, @NotNull String sku, @NotNull String name,
                           @NotNull BigDecimal unitPrice, @NotNull String currency, @NotNull BigDecimal taxRatePercent,
                           boolean requiresPreparation, String station, String category, boolean active,
                           @Positive long version) {

        Product toProduct() {
            return new Product(new ProductId(productId), Sku.of(sku), name,
                    Money.of(unitPrice, Currency.getInstance(currency)), new TaxRate(taxRatePercent),
                    requiresPreparation, station, category, active, version);
        }
    }
}
