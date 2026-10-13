package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.order.Order;
import com.fleet.storefront.domain.order.OrderAccount;
import com.fleet.storefront.domain.payment.Payment;
import com.fleet.storefront.domain.refund.Refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code Order} in docs/API.md: the fiscal document plus its settlement state. */
public record OrderResponse(
        UUID id,
        String orderNumber,
        String status,
        String terminalId,
        Instant placedAt,
        String currency,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal total,
        BigDecimal amountPaid,
        BigDecimal amountDue,
        BigDecimal amountRefunded,
        String fiscalHash,
        List<Line> lines,
        List<PaymentResponse> payments,
        List<RefundResponse> refunds) {

    public record Line(int lineNo, String sku, String name, int quantity, BigDecimal unitPrice,
                       BigDecimal taxRatePercent, BigDecimal tax, BigDecimal total, int refundedQuantity) {
    }

    static OrderResponse from(OrderAccount account) {
        Order order = account.order();
        return new OrderResponse(
                order.id().value(),
                order.number().value(),
                order.status().name(),
                order.terminalId().value(),
                order.placedAt(),
                order.currency().getCurrencyCode(),
                order.subtotal().amount(),
                order.taxTotal().amount(),
                order.total().amount(),
                account.amountPaid().amount(),
                account.amountDue().amount(),
                account.amountRefunded().amount(),
                order.fiscalHash(),
                order.lines().stream()
                        .map(l -> new Line(l.lineNo(), l.sku().value(), l.name(), l.quantity().value(),
                                l.unitPrice().amount(), l.taxRate().percent(), l.tax().amount(), l.total().amount(),
                                account.refundedQuantity(l.lineNo())))
                        .toList(),
                account.payments().stream().map(PaymentResponse::from).toList(),
                account.refunds().stream().map(RefundResponse::from).toList());
    }

    /** {@code Payment} in docs/API.md. */
    public record PaymentResponse(UUID id, String method, BigDecimal amount, BigDecimal tendered, BigDecimal change,
                                  String reference, String terminalId, Instant receivedAt, Instant voidedAt,
                                  String voidReason) {

        static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.id().value(), p.method().name(), p.amount().amount(), valueOf(p.tendered()),
                    valueOf(p.change()), p.reference(), p.terminalId().value(), p.receivedAt(), p.voidedAt(),
                    p.voidReason());
        }
    }

    /** {@code Refund} in docs/API.md. */
    public record RefundResponse(UUID id, String refundNumber, String method, String reason, boolean restocked,
                                 Instant issuedAt, String terminalId, BigDecimal subtotal, BigDecimal taxTotal,
                                 BigDecimal total, String fiscalHash, List<Line> lines) {

        public record Line(int lineNo, String sku, String name, int quantity, BigDecimal net, BigDecimal tax,
                           BigDecimal total) {
        }

        static RefundResponse from(Refund r) {
            return new RefundResponse(r.id().value(), r.number().value(), r.method().name(), r.reason(), r.restocked(),
                    r.issuedAt(), r.terminalId().value(), r.subtotal().amount(), r.taxTotal().amount(),
                    r.total().amount(), r.fiscalHash(),
                    r.lines().stream()
                            .map(l -> new Line(l.lineNo(), l.sku().value(), l.name(), l.quantity().value(),
                                    l.net().amount(), l.tax().amount(), l.total().amount()))
                            .toList());
        }
    }

    private static BigDecimal valueOf(Money money) {
        return money == null ? null : money.amount();
    }
}
