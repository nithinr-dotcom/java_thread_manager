package com.fleet.storefront.domain.refund;

import com.fleet.storefront.domain.fiscal.FiscalChain;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.payment.PaymentMethod;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/** Immutable fiscal refund document, chained like orders but in its own {@code REF} series. */
public record Refund(RefundId id, RefundNumber number, OrderId orderId, PaymentMethod method, String reason,
                     boolean restocked, TerminalId terminalId, Instant issuedAt, Currency currency,
                     List<RefundLine> lines, String fiscalHash) {

    public Refund {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(fiscalHash, "fiscalHash");
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw new IllegalArgumentException("Refund reason must be 1..200 characters");
        }
        lines = List.copyOf(lines);
        new RefundDraft(orderId, currency, lines); // same content rules
    }

    static Refund issue(RefundId id, RefundNumber number, RefundDraft draft, PaymentMethod method, String reason,
                        boolean restocked, TerminalId terminalId, Instant issuedAt, String previousHash) {
        String canonical = canonicalForm(number, draft.orderId(), terminalId, issuedAt, draft.currency(), method,
                restocked, reason, draft.lines());
        return new Refund(id, number, draft.orderId(), method, reason, restocked, terminalId, issuedAt,
                draft.currency(), draft.lines(), FiscalChain.link(previousHash, canonical));
    }

    public Money subtotal() {
        return draft().subtotal();
    }

    public Money taxTotal() {
        return draft().taxTotal();
    }

    public Money total() {
        return draft().total();
    }

    public String canonicalForm() {
        return canonicalForm(number, orderId, terminalId, issuedAt, currency, method, restocked, reason, lines);
    }

    private RefundDraft draft() {
        return new RefundDraft(orderId, currency, lines);
    }

    private static String canonicalForm(RefundNumber number, OrderId orderId, TerminalId terminalId, Instant issuedAt,
                                        Currency currency, PaymentMethod method, boolean restocked, String reason,
                                        List<RefundLine> lines) {
        RefundDraft draft = new RefundDraft(orderId, currency, lines);
        StringBuilder s = new StringBuilder("REF|").append(number.value()).append('|').append(orderId)
                .append('|').append(terminalId.value()).append('|').append(issuedAt)
                .append('|').append(currency.getCurrencyCode()).append('|').append(method)
                .append('|').append(restocked).append('|').append(reason)
                .append('|').append(draft.subtotal().amount().toPlainString())
                .append('|').append(draft.taxTotal().amount().toPlainString())
                .append('|').append(draft.total().amount().toPlainString()).append('|');
        lines.forEach(l -> s.append(l.canonicalForm()));
        return s.toString();
    }
}
