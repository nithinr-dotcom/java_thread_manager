package com.fleet.storefront.domain.refund;

import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.order.Quantity;

import java.util.Objects;

/** Units of one order line given back, with the exact net and tax returned for them. */
public record RefundLine(int lineNo, ProductId productId, Sku sku, String name, Quantity quantity, Money net, Money tax) {

    public RefundLine {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(net, "net");
        Objects.requireNonNull(tax, "tax");
        net.requireSameCurrency(tax);
        if (net.isNegative() || tax.isNegative()) {
            throw new IllegalArgumentException("Refund amounts must not be negative");
        }
    }

    public Money total() {
        return net.plus(tax);
    }

    String canonicalForm() {
        return lineNo + "," + sku.value() + "," + quantity.value() + "," + net.amount().toPlainString() + ","
                + tax.amount().toPlainString() + ";";
    }
}
