package com.fleet.storefront.domain.order;

import com.fleet.storefront.domain.catalog.ProductId;
import com.fleet.storefront.domain.inventory.StockRequirement;
import com.fleet.storefront.domain.money.CurrencyMismatchException;
import com.fleet.storefront.domain.money.Money;

import java.time.Instant;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A fully priced, validated basket that has not yet taken stock nor received a fiscal number.
 * All business validation happens here, <em>before</em> any lock is taken in the database.
 */
public record OrderDraft(Currency currency, List<OrderLine> lines) {

    public OrderDraft {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        Set<ProductId> products = new HashSet<>();
        Set<Integer> lineNumbers = new HashSet<>();
        for (OrderLine line : lines) {
            if (!line.unitPrice().currency().equals(currency)) {
                throw new CurrencyMismatchException(currency, line.unitPrice().currency());
            }
            if (!products.add(line.productId())) {
                throw new IllegalArgumentException("Product " + line.sku() + " appears on more than one line");
            }
            if (!lineNumbers.add(line.lineNo())) {
                throw new IllegalArgumentException("Duplicate line number " + line.lineNo());
            }
        }
    }

    public Money subtotal() {
        return lines.stream().map(OrderLine::net).reduce(Money.zero(currency), Money::plus);
    }

    public Money taxTotal() {
        return lines.stream().map(OrderLine::tax).reduce(Money.zero(currency), Money::plus);
    }

    public Money total() {
        return subtotal().plus(taxTotal());
    }

    /** Stock to take, in a deterministic (product id) order so concurrent checkouts lock rows identically. */
    public List<StockRequirement> stockRequirements() {
        return lines.stream()
                .map(l -> new StockRequirement(l.productId(), l.sku(), l.quantity()))
                .sorted(Comparator.comparing(StockRequirement::productId))
                .toList();
    }

    /**
     * Issues the fiscal document.
     *
     * @param previousHash hash of the previous order in the day's chain, {@code null} for the first one
     */
    public Order issue(OrderId id, OrderNumber number, TerminalId terminalId, Instant placedAt, String previousHash) {
        return Order.place(id, number, terminalId, this, placedAt, previousHash);
    }
}
