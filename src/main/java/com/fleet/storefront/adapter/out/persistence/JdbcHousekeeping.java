package com.fleet.storefront.adapter.out.persistence;

import com.fleet.storefront.application.port.out.Housekeeping;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Batched retention deletes. Each statement deletes at most {@code batchSize} rows picked through an
 * index range scan, auto-commits, and skips rows another transaction holds ({@code SKIP LOCKED}), so a
 * purge never waits behind — or blocks — a checkout or the outbox relay.
 */
@Repository
class JdbcHousekeeping implements Housekeeping {

    private final JdbcClient jdbc;

    JdbcHousekeeping(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteIdempotencyKeysBefore(Instant before, int batchSize) {
        return jdbc.sql("""
                        DELETE FROM idempotency_key
                         WHERE (terminal_id, idempotency_key) IN (
                               SELECT terminal_id, idempotency_key FROM idempotency_key
                                WHERE created_at < :before
                                LIMIT :batch
                                FOR UPDATE SKIP LOCKED)
                        """)
                .param("before", Timestamp.from(before))
                .param("batch", batchSize)
                .update();
    }

    @Override
    public int deletePublishedOutboxEventsBefore(Instant before, int batchSize) {
        return jdbc.sql("""
                        DELETE FROM outbox_event
                         WHERE id IN (SELECT id FROM outbox_event
                                       WHERE published_at IS NOT NULL AND published_at < :before
                                       LIMIT :batch
                                       FOR UPDATE SKIP LOCKED)
                        """)
                .param("before", Timestamp.from(before))
                .param("batch", batchSize)
                .update();
    }
}
