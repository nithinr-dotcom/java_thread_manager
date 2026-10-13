package com.fleet.storefront.adapter.out.kitchen;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.storefront.adapter.out.outbox.NonRetryableDispatchException;
import com.fleet.storefront.adapter.out.outbox.OutboxDispatcher;
import com.fleet.storefront.adapter.out.outbox.OutboxRecord;
import com.fleet.storefront.application.port.out.Outbox;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.order.event.KitchenTicketCancelled;
import com.fleet.storefront.domain.order.event.KitchenTicketRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** Hands kitchen events from the outbox to the KDS inbox. */
@Component
class KitchenTicketDispatcher implements OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(KitchenTicketDispatcher.class);

    private final KitchenTicketInbox inbox;
    private final ObjectMapper json;

    KitchenTicketDispatcher(KitchenTicketInbox inbox, ObjectMapper json) {
        this.inbox = inbox;
        this.json = json;
    }

    @Override
    public String destination() {
        return Outbox.Destination.KITCHEN.name();
    }

    @Override
    public void dispatch(OutboxRecord record) throws Exception {
        switch (record.eventType()) {
            case KitchenTicketRequested.TYPE -> {
                KitchenTicketRequested request = read(record, KitchenTicketRequested.class);
                boolean created = inbox.accept(request, json.writeValueAsString(withStations(request.items())));
                log.info("Kitchen ticket for {} {}", request.orderNumber(), created ? "queued" : "already queued (redelivery)");
            }
            case KitchenTicketCancelled.TYPE -> {
                KitchenTicketCancelled cancellation = read(record, KitchenTicketCancelled.class);
                boolean changed = inbox.cancel(cancellation, json.writeValueAsString(withStations(cancellation.items())));
                log.info("Kitchen ticket for {} {}", cancellation.orderNumber(),
                        changed ? "cancelled" : "already served or cancelled");
            }
            default -> throw new NonRetryableDispatchException("Unknown kitchen event type " + record.eventType(), null);
        }
    }

    private <T> T read(OutboxRecord record, Class<T> type) {
        try {
            return json.readValue(record.payload(), type);
        } catch (JacksonException e) {
            throw new NonRetryableDispatchException("Unreadable " + record.eventType() + " payload", e);
        }
    }

    /** Events written before stations existed carry none: they belong to the default station. */
    private static List<KitchenTicketRequested.Item> withStations(List<KitchenTicketRequested.Item> items) {
        return items.stream()
                .map(i -> i.station() != null ? i
                        : new KitchenTicketRequested.Item(i.sku(), i.name(), i.quantity(), Product.DEFAULT_STATION))
                .toList();
    }
}
