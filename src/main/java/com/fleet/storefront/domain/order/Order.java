package com.fleet.storefront.domain.order;

import com.fleet.storefront.domain.fiscal.FiscalChain;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.order.event.KitchenTicketCancelled;
import com.fleet.storefront.domain.order.event.KitchenTicketRequested;
import com.fleet.storefront.domain.order.event.OrderPlaced;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Order aggregate root. The fiscal content (number, lines, totals, timestamp, hash) is immutable once
 * placed; corrections are new documents (refunds). Only the lifecycle {@link OrderStatus} changes,
 * through {@link OrderAccount}.
 */
public final class Order {

    private final OrderId id;
    private final OrderNumber number;
    private final TerminalId terminalId;
    private final OrderDraft content;
    private final OrderStatus status;
    private final Instant placedAt;
    private final String fiscalHash;

    private Order(OrderId id, OrderNumber number, TerminalId terminalId, OrderDraft content,
                  OrderStatus status, Instant placedAt, String fiscalHash) {
        this.id = Objects.requireNonNull(id, "id");
        this.number = Objects.requireNonNull(number, "number");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.content = Objects.requireNonNull(content, "content");
        this.status = Objects.requireNonNull(status, "status");
        this.placedAt = Objects.requireNonNull(placedAt, "placedAt");
        this.fiscalHash = fiscalHash;
    }

    static Order place(OrderId id, OrderNumber number, TerminalId terminalId, OrderDraft draft, Instant placedAt,
                       String previousHash) {
        Order unsigned = new Order(id, number, terminalId, draft, OrderStatus.PLACED, placedAt, null);
        return new Order(id, number, terminalId, draft, OrderStatus.PLACED, placedAt,
                FiscalChain.link(previousHash, unsigned.canonicalForm()));
    }

    /**
     * Reconstitutes a persisted order; totals are re-derived from the lines, never trusted from storage.
     *
     * @param fiscalHash {@code null} for documents issued before hash chaining existed
     */
    public static Order restore(OrderId id, OrderNumber number, TerminalId terminalId, Currency currency,
                                List<OrderLine> lines, OrderStatus status, Instant placedAt, String fiscalHash) {
        return new Order(id, number, terminalId, new OrderDraft(currency, lines), status, placedAt, fiscalHash);
    }

    Order withStatus(OrderStatus newStatus) {
        return new Order(id, number, terminalId, content, newStatus, placedAt, fiscalHash);
    }

    /**
     * The fiscally relevant content in a stable textual form; input of the hash chain. Never includes the
     * mutable status.
     */
    public String canonicalForm() {
        StringBuilder s = new StringBuilder("ORD|").append(number.value()).append('|').append(terminalId.value())
                .append('|').append(placedAt).append('|').append(currency().getCurrencyCode())
                .append('|').append(subtotal().amount().toPlainString())
                .append('|').append(taxTotal().amount().toPlainString())
                .append('|').append(total().amount().toPlainString()).append('|');
        lines().forEach(l -> s.append(l.canonicalForm()));
        return s.toString();
    }

    public Optional<KitchenTicketRequested> kitchenTicketRequest() {
        List<KitchenTicketRequested.Item> items = kitchenItems();
        if (items.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new KitchenTicketRequested(id.value(), number.value(), terminalId.value(), placedAt, items));
    }

    public Optional<KitchenTicketCancelled> kitchenTicketCancellation(Instant cancelledAt) {
        List<KitchenTicketRequested.Item> items = kitchenItems();
        if (items.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new KitchenTicketCancelled(id.value(), number.value(), terminalId.value(), cancelledAt, items));
    }

    private List<KitchenTicketRequested.Item> kitchenItems() {
        return lines().stream()
                .filter(OrderLine::requiresPreparation)
                .map(l -> new KitchenTicketRequested.Item(l.sku().value(), l.name(), l.quantity().value(), l.station()))
                .toList();
    }

    public OrderPlaced placedEvent() {
        return new OrderPlaced(id.value(), number.value(), terminalId.value(), currency().getCurrencyCode(),
                subtotal().amount(), taxTotal().amount(), total().amount(), placedAt,
                lines().stream()
                        .map(l -> new OrderPlaced.Line(l.lineNo(), l.productId().value(), l.sku().value(),
                                l.quantity().value(), l.unitPrice().amount(), l.taxRate().percent(),
                                l.tax().amount(), l.total().amount()))
                        .toList(),
                fiscalHash);
    }

    public Optional<OrderLine> line(int lineNo) {
        return lines().stream().filter(l -> l.lineNo() == lineNo).findFirst();
    }

    public OrderId id() {
        return id;
    }

    public OrderNumber number() {
        return number;
    }

    public TerminalId terminalId() {
        return terminalId;
    }

    public Currency currency() {
        return content.currency();
    }

    public List<OrderLine> lines() {
        return content.lines();
    }

    public Money subtotal() {
        return content.subtotal();
    }

    public Money taxTotal() {
        return content.taxTotal();
    }

    public Money total() {
        return content.total();
    }

    public OrderStatus status() {
        return status;
    }

    public Instant placedAt() {
        return placedAt;
    }

    /** SHA-256 chain link of this document, or {@code null} for documents issued before chaining existed. */
    public String fiscalHash() {
        return fiscalHash;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Order other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Order[" + number + ", " + total() + ", " + status + "]";
    }
}
