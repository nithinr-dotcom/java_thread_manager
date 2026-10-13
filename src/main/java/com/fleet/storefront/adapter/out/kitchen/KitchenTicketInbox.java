package com.fleet.storefront.adapter.out.kitchen;

import com.fleet.storefront.domain.order.event.KitchenTicketCancelled;
import com.fleet.storefront.domain.order.event.KitchenTicketRequested;
import com.fleet.storefront.domain.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The Kitchen Display System's intake. {@code UNIQUE (order_id)} makes it an idempotent consumer,
 * turning the outbox's at-least-once delivery into exactly one ticket per order. Request and
 * cancellation may arrive in either order: a cancellation creates the ticket already cancelled, and a
 * late request never resurrects it.
 */
@Repository
public class KitchenTicketInbox {

    private final JdbcClient jdbc;

    KitchenTicketInbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code true} if a new ticket was queued, {@code false} if it was a redelivery */
    public boolean accept(KitchenTicketRequested request, String itemsJson) {
        return jdbc.sql("""
                        INSERT INTO kitchen_ticket (id, order_id, order_number, terminal_id, items, status, updated_at)
                        VALUES (:id, :orderId, :number, :terminal, CAST(:items AS jsonb), 'QUEUED', clock_timestamp())
                        ON CONFLICT (order_id) DO NOTHING
                        """)
                .param("id", UuidV7.next())
                .param("orderId", request.orderId())
                .param("number", request.orderNumber())
                .param("terminal", request.terminalId())
                .param("items", itemsJson)
                .update() == 1;
    }

    /** @return {@code true} if a ticket changed to (or was created as) cancelled */
    public boolean cancel(KitchenTicketCancelled cancellation, String itemsJson) {
        return jdbc.sql("""
                        INSERT INTO kitchen_ticket (id, order_id, order_number, terminal_id, items, status, updated_at)
                        VALUES (:id, :orderId, :number, :terminal, CAST(:items AS jsonb), 'CANCELLED', clock_timestamp())
                        ON CONFLICT (order_id) DO UPDATE
                           SET status = 'CANCELLED', updated_at = clock_timestamp()
                         WHERE kitchen_ticket.status NOT IN ('SERVED', 'CANCELLED')
                        """)
                .param("id", UuidV7.next())
                .param("orderId", cancellation.orderId())
                .param("number", cancellation.orderNumber())
                .param("terminal", cancellation.terminalId())
                .param("items", itemsJson)
                .update() == 1;
    }
}
