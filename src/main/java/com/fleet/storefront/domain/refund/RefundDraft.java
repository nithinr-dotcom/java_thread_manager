package com.fleet.storefront.domain.refund;

import com.fleet.storefront.domain.inventory.StockRequirement;
import com.fleet.storefront.domain.money.CurrencyMismatchException;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.payment.PaymentMethod;

import java.time.Instant;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/** A validated, priced refund that has no fiscal number yet. Built by {@code OrderAccount.draftRefund}. */
public record RefundDraft(OrderId orderId, Currency currency, List<RefundLine> lines) {

    public RefundDraft {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(currency, "currency");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A refund needs at least one line");
        }
        for (RefundLine line : lines) {
            if (!line.net().currency().equals(currency)) {
                throw new CurrencyMismatchException(currency, line.net().currency());
            }
        }
    }

    public Money subtotal() {
        return lines.stream().map(RefundLine::net).reduce(Money.zero(currency), Money::plus);
    }

    public Money taxTotal() {
        return lines.stream().map(RefundLine::tax).reduce(Money.zero(currency), Money::plus);
    }

    public Money total() {
        return subtotal().plus(taxTotal());
    }

    /** Units to put back on the shelf, in product-id order (the global stock lock order). */
    public List<StockRequirement> stockToReturn() {
        return lines.stream()
                .map(l -> new StockRequirement(l.productId(), l.sku(), l.quantity()))
                .sorted(Comparator.comparing(StockRequirement::productId))
                .toList();
    }

    public Refund issue(RefundId id, RefundNumber number, PaymentMethod method, String reason, boolean restocked,
                        TerminalId terminalId, Instant issuedAt, String previousHash) {
        return Refund.issue(id, number, this, method, reason, restocked, terminalId, issuedAt, previousHash);
    }
}
