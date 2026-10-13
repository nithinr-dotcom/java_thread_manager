package com.fleet.storefront.domain.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Replicated to HQ: full fiscal record of a refund document. */
public record RefundIssued(UUID refundId, String refundNumber, UUID orderId, String orderNumber, String method,
                           String reason, boolean restocked, String currency, BigDecimal subtotal,
                           BigDecimal taxTotal, BigDecimal total, String terminalId, Instant issuedAt,
                           List<Line> lines, String fiscalHash) {

    public static final String TYPE = "RefundIssued";

    public RefundIssued {
        lines = List.copyOf(lines);
    }

    public record Line(int lineNo, String sku, int quantity, BigDecimal net, BigDecimal tax, BigDecimal total) {
    }
}
