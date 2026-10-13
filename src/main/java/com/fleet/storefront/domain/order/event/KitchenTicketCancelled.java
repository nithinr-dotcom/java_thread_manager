package com.fleet.storefront.domain.order.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Emitted when an order with prepared items is cancelled. Carries the full ticket content so the
 * kitchen can record the cancellation even if it never received the original request (the two events
 * may be delivered in either order).
 */
public record KitchenTicketCancelled(UUID orderId, String orderNumber, String terminalId, Instant cancelledAt,
                                     List<KitchenTicketRequested.Item> items) {

    public static final String TYPE = "KitchenTicketCancelled";

    public KitchenTicketCancelled {
        items = List.copyOf(items);
    }
}
