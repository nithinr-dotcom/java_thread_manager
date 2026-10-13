package com.fleet.storefront.domain.payment;

import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.TerminalId;

import java.time.Instant;
import java.util.Objects;

/**
 * A tender taken for an order. Immutable.
 *
 * @param tendered cash handed over (CASH only)
 * @param change   cash returned, {@code tendered - amount} (CASH only)
 * @param reference approval code from the card terminal (CARD only)
 * @param voidedAt  set when the tender was handed back before the order was completed
 */
public record Payment(PaymentId id, OrderId orderId, PaymentMethod method, Money amount, Money tendered,
                      Money change, String reference, TerminalId terminalId, Instant receivedAt, Instant voidedAt,
                      String voidReason) {

    public Payment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("Payment amount must not be negative: " + amount);
        }
        if ((voidedAt == null) != (voidReason == null)) {
            throw new IllegalArgumentException("A voided payment needs both a time and a reason");
        }
        switch (method) {
            case CASH -> {
                Objects.requireNonNull(tendered, "tendered");
                Objects.requireNonNull(change, "change");
                if (!tendered.minus(amount).equals(change)) {
                    throw new IllegalArgumentException("Change must equal tendered - amount");
                }
                if (reference != null) {
                    throw new IllegalArgumentException("Cash payments have no reference");
                }
            }
            case CARD -> {
                if (tendered != null || change != null) {
                    throw new IllegalArgumentException("Card payments have no tendered amount or change");
                }
                if (reference == null || reference.isBlank() || reference.length() > 64) {
                    throw new IllegalArgumentException("Card payments need a 1..64 character approval reference");
                }
            }
        }
    }

    public static Payment cash(PaymentId id, OrderId orderId, Money amount, Money tendered, TerminalId terminalId,
                               Instant receivedAt) {
        if (tendered.compareTo(amount) < 0) {
            throw new InsufficientTenderException(amount, tendered);
        }
        return new Payment(id, orderId, PaymentMethod.CASH, amount, tendered, tendered.minus(amount), null,
                terminalId, receivedAt, null, null);
    }

    public static Payment card(PaymentId id, OrderId orderId, Money amount, String reference, TerminalId terminalId,
                               Instant receivedAt) {
        return new Payment(id, orderId, PaymentMethod.CARD, amount, null, null, reference, terminalId, receivedAt,
                null, null);
    }

    public boolean voided() {
        return voidedAt != null;
    }

    public Payment voidAt(Instant at, String reason) {
        return new Payment(id, orderId, method, amount, tendered, change, reference, terminalId, receivedAt, at, reason);
    }
}
