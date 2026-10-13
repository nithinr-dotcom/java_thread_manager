package com.fleet.storefront.adapter.out.outbox;

import com.fleet.storefront.application.port.out.OutboxMonitor;
import com.fleet.storefront.application.readmodel.DeadLetterView;
import com.fleet.storefront.application.readmodel.OutboxDestinationStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only aggregates over {@code outbox_event} (no locks, never competes with the relay), plus re-queueing. */
@Repository
class JdbcOutboxMonitor implements OutboxMonitor {

    private final JdbcClient jdbc;

    JdbcOutboxMonitor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OutboxDestinationStatus> status() {
        return jdbc.sql("""
                        SELECT destination,
                               count(*) FILTER (WHERE published_at IS NULL AND dead_at IS NULL)               AS pending,
                               count(*) FILTER (WHERE published_at IS NOT NULL)                              AS published,
                               count(*) FILTER (WHERE published_at IS NULL AND dead_at IS NULL
                                                      AND last_error IS NOT NULL)                            AS failing,
                               count(*) FILTER (WHERE dead_at IS NOT NULL AND published_at IS NULL)          AS dead,
                               min(created_at) FILTER (WHERE published_at IS NULL AND dead_at IS NULL)       AS oldest_pending,
                               (array_agg(last_error ORDER BY next_attempt_at DESC)
                                    FILTER (WHERE published_at IS NULL AND dead_at IS NULL
                                                  AND last_error IS NOT NULL))[1]                            AS last_error
                          FROM outbox_event
                         GROUP BY destination
                         ORDER BY destination
                        """)
                .query((rs, n) -> {
                    Timestamp oldest = rs.getTimestamp("oldest_pending");
                    return new OutboxDestinationStatus(
                            rs.getString("destination"),
                            rs.getLong("pending"),
                            rs.getLong("published"),
                            rs.getLong("failing"),
                            rs.getLong("dead"),
                            Optional.ofNullable(oldest).map(Timestamp::toInstant),
                            Optional.ofNullable(rs.getString("last_error")));
                })
                .list();
    }

    @Override
    public List<DeadLetterView> deadLetters(int limit) {
        return jdbc.sql("""
                        SELECT id, destination, event_type, aggregate_id, attempts, last_error, created_at, dead_at
                          FROM outbox_event
                         WHERE dead_at IS NOT NULL AND published_at IS NULL
                         ORDER BY dead_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new DeadLetterView(
                        rs.getObject("id", UUID.class),
                        rs.getString("destination"),
                        rs.getString("event_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("dead_at").toInstant()))
                .list();
    }

    @Override
    public int requeueAll(String destination) {
        return jdbc.sql("""
                        UPDATE outbox_event
                           SET dead_at = NULL, attempts = 0, next_attempt_at = now()
                         WHERE destination = :destination AND dead_at IS NOT NULL AND published_at IS NULL
                        """)
                .param("destination", destination)
                .update();
    }

    @Override
    public boolean requeue(UUID eventId) {
        return jdbc.sql("""
                        UPDATE outbox_event
                           SET dead_at = NULL, attempts = 0, next_attempt_at = now()
                         WHERE id = :id AND dead_at IS NOT NULL AND published_at IS NULL
                        """)
                .param("id", eventId)
                .update() == 1;
    }
}
