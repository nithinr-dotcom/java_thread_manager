package com.fleet.storefront.domain.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Full fiscal record of a placed order, replicated to Central HQ when connectivity allows. */
public record OrderPlaced(
        UUID orderId,
        String orderNumber,
        String terminalId,
        String currency,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal total,
        Instant placedAt,
        List<Line> lines,
        String fiscalHash) {

    public static final String TYPE = "OrderPlaced";

    public OrderPlaced {
        lines = List.copyOf(lines);
    }

    public record Line(int lineNo, UUID productId, String sku, int quantity, BigDecimal unitPrice,
                       BigDecimal taxRatePercent, BigDecimal tax, BigDecimal total) {
    }
}
