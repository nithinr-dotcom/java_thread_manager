package com.fleet.storefront.domain.order.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Emitted when a placed order contains items that a kitchen station must prepare. */
public record KitchenTicketRequested(UUID orderId, String orderNumber, String terminalId, Instant placedAt, List<Item> items) {

    public static final String TYPE = "KitchenTicketRequested";

    public KitchenTicketRequested {
        items = List.copyOf(items);
    }

    /** @param station preparing station; {@code null} in events written before stations existed */
    public record Item(String sku, String name, int quantity, String station) {
    }
}
