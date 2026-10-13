package com.fleet.storefront.domain.catalog;

import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sellable item as known by this node's catalog.
 *
 * @param requiresPreparation whether the item must be prepared by a kitchen station (triggers a KDS ticket)
 * @param station             kitchen station that prepares it (e.g. {@code GRILL}, {@code BAR})
 * @param category            grouping for the POS menu
 * @param active              inactive products are hidden from the menu and cannot be ordered
 * @param version             last-writer-wins version shared with HQ (epoch milliseconds)
 */
public record Product(ProductId id, Sku sku, String name, Money unitPrice, TaxRate taxRate, boolean requiresPreparation,
                      String station, String category, boolean active, long version) {

    public static final String DEFAULT_STATION = "KITCHEN";
    public static final String DEFAULT_CATEGORY = "General";
    private static final Pattern STATION = Pattern.compile("[A-Z0-9_-]{1,32}");

    public Product {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(taxRate, "taxRate");
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException("Product name must be 1..200 characters");
        }
        if (unitPrice.isNegative()) {
            throw new IllegalArgumentException("Unit price must not be negative: " + unitPrice);
        }
        station = station == null ? DEFAULT_STATION : station.trim().toUpperCase(java.util.Locale.ROOT);
        if (!STATION.matcher(station).matches()) {
            throw new IllegalArgumentException("Invalid station: '" + station + "'");
        }
        category = category == null || category.isBlank() ? DEFAULT_CATEGORY : category.trim();
        if (category.length() > 64) {
            throw new IllegalArgumentException("Category must be at most 64 characters");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Version must not be negative: " + version);
        }
    }

    /** An active product at its initial version, at the default station and category. */
    public Product(ProductId id, Sku sku, String name, Money unitPrice, TaxRate taxRate, boolean requiresPreparation) {
        this(id, sku, name, unitPrice, taxRate, requiresPreparation, DEFAULT_STATION, DEFAULT_CATEGORY, true, 0);
    }

    public Product withVersion(long newVersion) {
        return new Product(id, sku, name, unitPrice, taxRate, requiresPreparation, station, category, active, newVersion);
    }
}
