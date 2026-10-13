package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.BrowseCatalogQuery;
import com.fleet.storefront.application.port.in.ManageCatalogUseCase;
import com.fleet.storefront.application.port.in.SyncCatalogUseCase;
import com.fleet.storefront.application.port.in.UnknownProductException;
import com.fleet.storefront.application.port.out.HqCatalogFeed;
import com.fleet.storefront.application.port.out.InventoryLedger;
import com.fleet.storefront.application.port.out.ProductCatalog;
import com.fleet.storefront.application.port.out.TransactionRunner;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductExistsException;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.ProductVersionConflictException;
import com.fleet.storefront.domain.money.CurrencyMismatchException;
import com.fleet.storefront.domain.money.TaxRate;
import com.fleet.storefront.domain.shared.UuidV7;
import com.fleet.storefront.domain.shared.ValidationException;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Catalog reads, back-office maintenance and inbound HQ sync. Versions are epoch milliseconds shared
 * with HQ: a local edit gets {@code max(version + 1, now)}, so last-writer-wins on version is
 * last-writer-wins on time across the node and HQ.
 */
public final class CatalogService implements BrowseCatalogQuery, ManageCatalogUseCase, SyncCatalogUseCase {

    private static final System.Logger LOG = System.getLogger(CatalogService.class.getName());

    private final ProductCatalog catalog;
    private final InventoryLedger inventory;
    private final TransactionRunner transactions;
    private final HqCatalogFeed hqFeed;
    private final BranchContext branch;
    private final Clock clock;

    public CatalogService(ProductCatalog catalog, InventoryLedger inventory, TransactionRunner transactions,
                          HqCatalogFeed hqFeed, BranchContext branch, Clock clock) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.transactions = transactions;
        this.hqFeed = hqFeed;
        this.branch = branch;
        this.clock = clock;
    }

    @Override
    public List<CatalogItem> catalog(boolean includeInactive) {
        List<Product> products = catalog.findAll(includeInactive);
        Map<ProductId, Integer> stock = inventory.onHand(products.stream().map(Product::id).toList());
        return products.stream()
                .map(p -> new CatalogItem(p, stock.getOrDefault(p.id(), 0)))
                .toList();
    }

    @Override
    public CatalogItem create(ProductDefinition definition) {
        return transactions.inTransaction(() -> {
            if (catalog.findBySku(definition.sku()).isPresent()) {
                throw new ProductExistsException(definition.sku());
            }
            Product product = toProduct(new ProductId(UuidV7.next()), definition, true, clock.millis());
            catalog.insert(product);
            return new CatalogItem(product, 0);
        });
    }

    @Override
    public CatalogItem update(ProductDefinition definition, boolean active, long expectedVersion) {
        return transactions.inTransaction(() -> {
            Product current = catalog.findBySku(definition.sku())
                    .orElseThrow(() -> new UnknownProductException(List.of(definition.sku())));
            if (current.version() != expectedVersion) {
                throw new ProductVersionConflictException(definition.sku(), current.version());
            }
            Product updated = toProduct(current.id(), definition, active,
                    Math.max(current.version() + 1, clock.millis()));
            if (!catalog.update(updated, expectedVersion)) {
                long now = catalog.findBySku(definition.sku()).map(Product::version).orElse(-1L);
                throw new ProductVersionConflictException(definition.sku(), now);
            }
            int onHand = inventory.onHand(List.of(updated.id())).getOrDefault(updated.id(), 0);
            return new CatalogItem(updated, onHand);
        });
    }

    @Override
    public SyncResult apply(List<Product> products) {
        for (Product p : products) {
            if (!p.unitPrice().currency().equals(branch.currency())) {
                throw new CurrencyMismatchException(branch.currency(), p.unitPrice().currency());
            }
        }
        // Product-id order: concurrent pushes and pulls lock rows in the same order (no deadlock).
        List<Product> ordered = products.stream().sorted(Comparator.comparing(Product::id)).toList();
        return transactions.inTransaction(() -> {
            int applied = 0;
            for (Product p : ordered) {
                boolean sameSkuOtherId = catalog.findBySku(p.sku()).filter(local -> !local.id().equals(p.id())).isPresent();
                if (sameSkuOtherId) {
                    LOG.log(System.Logger.Level.WARNING, "HQ product {0} ({1}) conflicts with a local product of the "
                            + "same SKU; skipped until resolved in the back office", p.id(), p.sku());
                } else if (catalog.upsertIfNewer(p)) {
                    applied++;
                }
            }
            return new SyncResult(applied, products.size() - applied);
        });
    }

    @Override
    public SyncResult pullFromHq() {
        long since = catalog.hqCatalogWatermark();
        List<Product> changes = hqFeed.changesSince(branch.branchCode(), since);
        if (changes.isEmpty()) {
            return new SyncResult(0, 0);
        }
        SyncResult result = apply(changes);
        long watermark = changes.stream().mapToLong(Product::version).max().orElse(since);
        transactions.inTransaction(() -> {
            catalog.saveHqCatalogWatermark(Math.max(watermark, since));
            return null;
        });
        return result;
    }

    private Product toProduct(ProductId id, ProductDefinition d, boolean active, long version) {
        TaxRate taxRate;
        try {
            taxRate = new TaxRate(d.taxRatePercent());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
        try {
            return new Product(id, d.sku(), d.name().trim(), Amounts.exact(d.unitPrice(), branch.currency(), "unitPrice"),
                    taxRate, d.requiresPreparation(), d.station(), d.category(), active, version);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
    }
}
