package com.fleet.storefront.adapter.out.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Lease-based claiming: a claim moves {@code next_attempt_at} past "now + lease" in one short
 * auto-committed statement. No transaction or lock is held while the downstream call runs. If the
 * node dies mid-delivery the lease simply expires and the row is picked up again after restart.
 * {@code FOR UPDATE SKIP LOCKED} lets several relay threads/instances drain concurrently.
 */
@Repository
public class OutboxStore {

    private final JdbcClient jdbc;

    OutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<OutboxRecord> claimDue(String destination, int limit, Duration lease) {
        return jdbc.sql("""
                        UPDATE outbox_event
                           SET next_attempt_at = now() + :leaseMs * INTERVAL '1 millisecond',
                               attempts = attempts + 1
                         WHERE id IN (SELECT id FROM outbox_event
                                       WHERE destination = :destination
                                         AND published_at IS NULL
                                         AND dead_at IS NULL
                                         AND next_attempt_at <= now()
                                       ORDER BY created_at, id
                                       LIMIT :limit
                                       FOR UPDATE SKIP LOCKED)
                        RETURNING id, destination, event_type, aggregate_id, payload::text AS payload, attempts, created_at
                        """)
                .param("leaseMs", lease.toMillis())
                .param("destination", destination)
                .param("limit", limit)
                .query((rs, n) -> new OutboxRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("destination"),
                        rs.getString("event_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        rs.getTimestamp("created_at").toInstant()))
                .list()
                .stream()
                .sorted(Comparator.comparing(OutboxRecord::createdAt).thenComparing(OutboxRecord::id))
                .toList();
    }

    public void markPublished(UUID id) {
        jdbc.sql("UPDATE outbox_event SET published_at = now(), last_error = NULL WHERE id = :id")
                .param("id", id)
                .update();
    }

    /** Stops retrying: the event needs an operator (see {@code /ops/outbox/dead}). */
    public void markDead(UUID id, String error) {
        jdbc.sql("UPDATE outbox_event SET dead_at = now(), last_error = :error WHERE id = :id")
                .param("error", truncate(error))
                .param("id", id)
                .update();
    }

    public void markFailed(UUID id, String error, Duration retryIn) {
        jdbc.sql("""
                        UPDATE outbox_event
                           SET next_attempt_at = now() + :retryMs * INTERVAL '1 millisecond', last_error = :error
                         WHERE id = :id
                        """)
                .param("retryMs", retryIn.toMillis())
                .param("error", truncate(error))
                .param("id", id)
                .update();
    }

    private static String truncate(String error) {
        return error != null && error.length() > 2000 ? error.substring(0, 2000) : error;
    }
}
