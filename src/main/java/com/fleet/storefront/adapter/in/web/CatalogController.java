package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.application.port.in.BrowseCatalogQuery;
import com.fleet.storefront.application.port.in.BrowseCatalogQuery.CatalogItem;
import com.fleet.storefront.application.port.in.ManageCatalogUseCase;
import com.fleet.storefront.application.port.in.ManageCatalogUseCase.ProductDefinition;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.Sku;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog")
class CatalogController {

    private final BrowseCatalogQuery browseCatalog;
    private final ManageCatalogUseCase manageCatalog;

    CatalogController(BrowseCatalogQuery browseCatalog, ManageCatalogUseCase manageCatalog) {
        this.browseCatalog = browseCatalog;
        this.manageCatalog = manageCatalog;
    }

    @GetMapping
    List<CatalogItemResponse> catalog(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return browseCatalog.catalog(includeInactive).stream().map(CatalogItemResponse::from).toList();
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    CatalogItemResponse create(@Valid @RequestBody CreateProductRequest request) {
        ProductDefinition definition = Requests.parse(() -> new ProductDefinition(Sku.of(request.sku()),
                request.name(), request.unitPrice(), request.taxRatePercent(), request.requiresPreparation(),
                request.station(), request.category()));
        return CatalogItemResponse.from(manageCatalog.create(definition));
    }

    @PutMapping("/products/{sku}")
    CatalogItemResponse update(@PathVariable String sku, @Valid @RequestBody UpdateProductRequest request) {
        ProductDefinition definition = Requests.parse(() -> new ProductDefinition(Sku.of(sku), request.name(),
                request.unitPrice(), request.taxRatePercent(), request.requiresPreparation(), request.station(),
                request.category()));
        return CatalogItemResponse.from(manageCatalog.update(definition, request.active(), request.version()));
    }

    record CatalogItemResponse(UUID productId, String sku, String name, BigDecimal unitPrice, String currency,
                               BigDecimal taxRatePercent, boolean requiresPreparation, String station,
                               String category, boolean active, long version, int onHand) {

        static CatalogItemResponse from(CatalogItem item) {
            Product p = item.product();
            return new CatalogItemResponse(p.id().value(), p.sku().value(), p.name(), p.unitPrice().amount(),
                    p.unitPrice().currency().getCurrencyCode(), p.taxRate().percent(), p.requiresPreparation(),
                    p.station(), p.category(), p.active(), p.version(), item.onHand());
        }
    }

    record CreateProductRequest(@NotBlank @Size(max = 64) String sku, @NotBlank @Size(max = 200) String name,
                                @NotNull @DecimalMin("0") BigDecimal unitPrice,
                                @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal taxRatePercent,
                                boolean requiresPreparation, @Size(min = 1, max = 32) String station,
                                @Size(min = 1, max = 64) String category) {
    }

    record UpdateProductRequest(@NotBlank @Size(max = 200) String name, @NotNull @DecimalMin("0") BigDecimal unitPrice,
                                @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal taxRatePercent,
                                boolean requiresPreparation, @Size(min = 1, max = 32) String station,
                                @Size(min = 1, max = 64) String category, @NotNull Boolean active,
                                @NotNull Long version) {
    }
}
