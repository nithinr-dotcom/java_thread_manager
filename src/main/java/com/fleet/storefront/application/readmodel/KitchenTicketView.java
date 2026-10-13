package com.fleet.storefront.application.readmodel;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A ticket as held by the kitchen inbox. Read-only projection; not a domain aggregate. */
public record KitchenTicketView(UUID id, UUID orderId, String orderNumber, String terminalId, String status,
                                Instant receivedAt, Instant updatedAt, List<Item> items) {

    public KitchenTicketView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderId, "orderId");
        items = List.copyOf(items);
    }

    public record Item(String sku, String name, int quantity, String station) {
    }
}
