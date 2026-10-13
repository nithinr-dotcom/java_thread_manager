package com.fleet.storefront.domain.order;

import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;

import java.util.Objects;

/**
 * Immutable order line. Product data (name, price, tax rate, station) is copied at checkout time so
 * later catalog changes never alter an issued fiscal document.
 */
public record OrderLine(
        int lineNo,
        ProductId productId,
        Sku sku,
        String name,
        Money unitPrice,
        Quantity quantity,
        TaxRate taxRate,
        boolean requiresPreparation,
        String station) {

    public OrderLine {
        if (lineNo < 1) {
            throw new IllegalArgumentException("Line numbers start at 1: " + lineNo);
        }
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(taxRate, "taxRate");
        if (unitPrice.isNegative()) {
            throw new IllegalArgumentException("Unit price must not be negative: " + unitPrice);
        }
        station = station == null ? Product.DEFAULT_STATION : station;
    }

    public OrderLine(int lineNo, ProductId productId, Sku sku, String name, Money unitPrice, Quantity quantity,
                     TaxRate taxRate, boolean requiresPreparation) {
        this(lineNo, productId, sku, name, unitPrice, quantity, taxRate, requiresPreparation, null);
    }

    public static OrderLine of(int lineNo, Product product, Quantity quantity) {
        return new OrderLine(lineNo, product.id(), product.sku(), product.name(), product.unitPrice(),
                quantity, product.taxRate(), product.requiresPreparation(), product.station());
    }

    public Money net() {
        return unitPrice.times(quantity.value());
    }

    public Money tax() {
        return taxRate.taxOn(net());
    }

    public Money total() {
        return net().plus(tax());
    }

    /** Fiscally relevant content, in a stable textual form (part of the document hash). */
    String canonicalForm() {
        return lineNo + "," + sku.value() + "," + name + "," + quantity.value() + "," + unitPrice.amount().toPlainString() + ","
                + taxRate.percent().toPlainString() + "," + tax().amount().toPlainString() + ","
                + total().amount().toPlainString() + ";";
    }
}
