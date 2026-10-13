package com.fleet.storefront.domain.order.event;

import java.time.Instant;
import java.util.UUID;

/** Replicated to HQ: an unpaid order was voided and its stock returned. */
public record OrderCancelled(UUID orderId, String orderNumber, String reason, String terminalId, Instant cancelledAt) {

    public static final String TYPE = "OrderCancelled";
}
