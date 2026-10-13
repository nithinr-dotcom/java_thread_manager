package com.fleet.storefront.domain.order;

import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.payment.Payment;
import com.fleet.storefront.domain.payment.PaymentAlreadyVoidedException;
import com.fleet.storefront.domain.payment.PaymentExceedsDueException;
import com.fleet.storefront.domain.payment.PaymentId;
import com.fleet.storefront.domain.payment.PaymentMethod;
import com.fleet.storefront.domain.payment.PaymentNotFoundException;
import com.fleet.storefront.domain.refund.Refund;
import com.fleet.storefront.domain.refund.RefundDraft;
import com.fleet.storefront.domain.refund.RefundExceedsSoldException;
import com.fleet.storefront.domain.refund.RefundLine;
import com.fleet.storefront.domain.refund.UnknownOrderLineException;
import com.fleet.storefront.domain.shared.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;

/**
 * An issued order together with its settlement: the payments taken and the refund documents issued
 * against it. Owns every lifecycle rule (pay, cancel, refund) and the derived amounts. Immutable; the
 * {@code with…} methods return the account after a change.
 */
public record OrderAccount(Order order, List<Payment> payments, List<Refund> refunds) {

    public OrderAccount {
        Objects.requireNonNull(order, "order");
        payments = List.copyOf(payments);
        refunds = List.copyOf(refunds);
    }

    public static OrderAccount of(Order order) {
        return new OrderAccount(order, List.of(), List.of());
    }

    public OrderStatus status() {
        return order.status();
    }

    /** Sum of the tenders taken and not voided. */
    public Money amountPaid() {
        return activePayments().stream().map(Payment::amount).reduce(Money.zero(order.currency()), Money::plus);
    }

    public List<Payment> activePayments() {
        return payments.stream().filter(p -> !p.voided()).toList();
    }

    public Money amountRefunded() {
        return refunds.stream().map(Refund::total).reduce(Money.zero(order.currency()), Money::plus);
    }

    /** What is still to be paid; zero unless the order is awaiting payment. */
    public Money amountDue() {
        return status() == OrderStatus.PLACED ? order.total().minus(amountPaid()) : Money.zero(order.currency());
    }

    public int refundedQuantity(int lineNo) {
        return refunds.stream().flatMap(r -> r.lines().stream())
                .filter(l -> l.lineNo() == lineNo)
                .mapToInt(l -> l.quantity().value())
                .sum();
    }

    // --- Payments ------------------------------------------------------------------------------

    /**
     * Takes a tender. Split tender is allowed (several payments), overpayment is not; cash change is
     * computed here, never by the client.
     *
     * @param tendered cash handed over (CASH), {@code null} for CARD
     * @param reference card approval code (CARD), {@code null} for CASH
     */
    public Payment pay(PaymentId id, PaymentMethod method, Money amount, Money tendered, String reference,
                       TerminalId terminalId, Instant receivedAt) {
        if (status() != OrderStatus.PLACED) {
            throw new OrderNotPayableException(order.number(), status());
        }
        Money due = amountDue();
        if (amount.compareTo(due) > 0) {
            throw new PaymentExceedsDueException(amount, due);
        }
        if (amount.amount().signum() <= 0 && due.amount().signum() > 0) {
            throw new ValidationException("Payment amount must be positive");
        }
        return switch (method) {
            case CASH -> Payment.cash(id, order.id(), amount, tendered, terminalId, receivedAt);
            case CARD -> Payment.card(id, order.id(), amount, reference, terminalId, receivedAt);
        };
    }

    /** The account after {@code payment}: {@code PAID} once payments cover the total. */
    public OrderAccount withPayment(Payment payment) {
        List<Payment> all = new ArrayList<>(payments);
        all.add(payment);
        OrderAccount provisional = new OrderAccount(order, all, refunds);
        OrderStatus next = provisional.amountPaid().compareTo(order.total()) >= 0 ? OrderStatus.PAID : OrderStatus.PLACED;
        return new OrderAccount(order.withStatus(next), all, refunds);
    }

    /**
     * Hands a tender back while the order is still awaiting payment (e.g. split tender abandoned). Once an
     * order is paid, money goes back only through a refund document.
     */
    public OrderAccount voidPayment(PaymentId paymentId, String reason, Instant at) {
        if (status() != OrderStatus.PLACED) {
            throw new OrderNotPayableException(order.number(), status());
        }
        Payment payment = payments.stream().filter(p -> p.id().equals(paymentId)).findFirst()
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.voided()) {
            throw new PaymentAlreadyVoidedException(paymentId);
        }
        List<Payment> all = payments.stream().map(p -> p.id().equals(paymentId) ? p.voidAt(at, reason) : p).toList();
        return new OrderAccount(order, all, refunds);
    }

    // --- Cancellation --------------------------------------------------------------------------

    /** Only an order awaiting payment with no (unvoided) tender can be cancelled; otherwise: void payments or refund. */
    public OrderAccount cancel() {
        if (status() != OrderStatus.PLACED || !activePayments().isEmpty()) {
            throw new OrderNotCancellableException(order.number(), status());
        }
        return new OrderAccount(order.withStatus(OrderStatus.CANCELLED), payments, refunds);
    }

    // --- Refunds -------------------------------------------------------------------------------

    /**
     * Prices a refund of the given units per line number. Net is exact (unit price × units); tax is
     * allocated proportionally on the <em>cumulative</em> refunded units of the line, so successive
     * partial refunds never round past the line's tax and refunding every unit returns exactly the
     * line total.
     */
    public RefundDraft draftRefund(SortedMap<Integer, Integer> unitsByLine) {
        if (!status().isRefundable()) {
            throw new OrderNotRefundableException(order.number(), status());
        }
        if (unitsByLine.isEmpty()) {
            throw new ValidationException("A refund needs at least one line");
        }
        List<RefundLine> lines = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : unitsByLine.entrySet()) {
            int lineNo = e.getKey();
            int units = e.getValue();
            OrderLine line = order.line(lineNo).orElseThrow(() -> new UnknownOrderLineException(lineNo));
            int before = refundedQuantity(lineNo);
            int refundable = line.quantity().value() - before;
            if (units < 1 || units > refundable) {
                throw new RefundExceedsSoldException(lineNo, units, refundable);
            }
            Money net = line.unitPrice().times(units);
            Money taxBefore = refundedTax(lineNo);
            Money taxAfter = proportionalTax(line, before + units);
            lines.add(new RefundLine(lineNo, line.productId(), line.sku(), line.name(), Quantity.of(units), net,
                    taxAfter.minus(taxBefore)));
        }
        return new RefundDraft(order.id(), order.currency(), lines);
    }

    /** The account after {@code refund}: {@code REFUNDED} once every unit is refunded. */
    public OrderAccount withRefund(Refund refund) {
        List<Refund> all = new ArrayList<>(refunds);
        all.add(refund);
        OrderAccount provisional = new OrderAccount(order, payments, all);
        boolean everything = order.lines().stream()
                .allMatch(l -> provisional.refundedQuantity(l.lineNo()) >= l.quantity().value());
        OrderStatus next = everything ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED;
        return new OrderAccount(order.withStatus(next), payments, all);
    }

    private Money refundedTax(int lineNo) {
        return refunds.stream().flatMap(r -> r.lines().stream())
                .filter(l -> l.lineNo() == lineNo)
                .map(RefundLine::tax)
                .reduce(Money.zero(order.currency()), Money::plus);
    }

    private static Money proportionalTax(OrderLine line, int units) {
        if (units == line.quantity().value()) {
            return line.tax();
        }
        int scale = line.tax().amount().scale();
        BigDecimal share = line.tax().amount().multiply(BigDecimal.valueOf(units))
                .divide(BigDecimal.valueOf(line.quantity().value()), scale, RoundingMode.HALF_EVEN);
        return Money.of(share, line.tax().currency());
    }
}
