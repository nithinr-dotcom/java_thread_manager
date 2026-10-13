package com.fleet.storefront.adapter.out.kitchen;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.storefront.application.port.out.KitchenBoard;
import com.fleet.storefront.application.readmodel.KitchenTicketView;
import com.fleet.storefront.domain.catalog.Product;
import com.fleet.storefront.domain.kitchen.KitchenTicketStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads what the kitchen inbox ({@link KitchenTicketInbox}) has accepted and applies station
 * transitions with a conditional UPDATE (compare-and-set on the current status).
 */
@Repository
class JdbcKitchenBoard implements KitchenBoard {

    private static final TypeReference<List<KitchenTicketView.Item>> ITEMS = new TypeReference<>() {
    };
    private static final String COLUMNS =
            "id, order_id, order_number, terminal_id, status, received_at, updated_at, items::text AS items";
    /** Items without a station (written before stations existed) belong to the default station. */
    private static final String STATION_FILTER = """
            (CAST(:station AS varchar) IS NULL
             OR items @> jsonb_build_array(jsonb_build_object('station', CAST(:station AS varchar)))
             OR (CAST(:station AS varchar) = 'KITCHEN' AND EXISTS (
                    SELECT 1 FROM jsonb_array_elements(items) i WHERE i->>'station' IS NULL)))
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RowMapper<KitchenTicketView> ticket;

    JdbcKitchenBoard(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.ticket = (rs, n) -> new KitchenTicketView(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("order_number"),
                rs.getString("terminal_id"),
                rs.getString("status"),
                rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                items(rs.getString("items")));
    }

    @Override
    public List<KitchenTicketView> recent(int limit, boolean activeOnly, Optional<String> station) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM kitchen_ticket WHERE "
                        + "(NOT :active OR status IN ('QUEUED', 'IN_PROGRESS', 'READY')) AND " + STATION_FILTER
                        + " ORDER BY received_at DESC, id DESC LIMIT :limit")
                .param("active", activeOnly)
                .param("station", station.orElse(null))
                .param("limit", limit)
                .query(ticket)
                .list();
    }

    @Override
    public Optional<KitchenTicketView> find(UUID ticketId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM kitchen_ticket WHERE id = :id")
                .param("id", ticketId)
                .query(ticket)
                .optional();
    }

    @Override
    public boolean transition(UUID ticketId, KitchenTicketStatus from, KitchenTicketStatus to) {
        return jdbc.sql("""
                        UPDATE kitchen_ticket SET status = :to, updated_at = clock_timestamp()
                         WHERE id = :id AND status = :from
                        """)
                .param("to", to.name())
                .param("id", ticketId)
                .param("from", from.name())
                .update() == 1;
    }

    @Override
    public List<KitchenTicketView> changedSince(Instant since, Optional<String> station) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM kitchen_ticket WHERE updated_at > :since AND " + STATION_FILTER
                        + " ORDER BY updated_at, id LIMIT 5000")
                .param("since", Timestamp.from(since))
                .param("station", station.orElse(null))
                .query(ticket)
                .list();
    }

    @Override
    public Instant now() {
        return jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
    }

    private List<KitchenTicketView.Item> items(String raw) {
        try {
            return json.readValue(raw, ITEMS).stream()
                    .map(i -> i.station() != null ? i
                            : new KitchenTicketView.Item(i.sku(), i.name(), i.quantity(), Product.DEFAULT_STATION))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Corrupt kitchen ticket items: " + raw, e);
        }
    }
}
