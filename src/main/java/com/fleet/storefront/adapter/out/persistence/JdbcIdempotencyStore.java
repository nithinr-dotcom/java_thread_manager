package com.fleet.storefront.adapter.out.persistence;

import com.fleet.storefront.application.port.out.IdempotencyStore;
import com.fleet.storefront.domain.order.TerminalId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code INSERT ... ON CONFLICT DO NOTHING} on the (terminal, key) primary key. If another
 * transaction inserted the same key and has not finished, PostgreSQL makes this statement wait for
 * it: on commit we get 0 rows and read the committed outcome; on rollback our insert goes through and
 * we process the request ourselves. There is no window in which two duplicates both proceed.
 *
 * <p>{@code resource_id} is whatever the key produced (order, payment or refund); {@code order_id} is
 * also filled when that resource is an order, for older releases that only read it.
 */
@Repository
class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;

    JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Claim claim(TerminalId terminalId, String key, String requestFingerprint, Instant now) {
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_key (terminal_id, idempotency_key, request_fingerprint, created_at)
                        VALUES (:terminal, :key, :fingerprint, :now)
                        ON CONFLICT (terminal_id, idempotency_key) DO NOTHING
                        """)
                .param("terminal", terminalId.value())
                .param("key", key)
                .param("fingerprint", requestFingerprint)
                .param("now", Timestamp.from(now))
                .update();
        if (inserted == 1) {
            return new Claimed();
        }
        return jdbc.sql("""
                        SELECT COALESCE(resource_id, order_id) AS resource_id, request_fingerprint FROM idempotency_key
                        WHERE terminal_id = :terminal AND idempotency_key = :key
                        """)
                .param("terminal", terminalId.value())
                .param("key", key)
                .query((rs, n) -> {
                    UUID resourceId = rs.getObject("resource_id", UUID.class);
                    if (resourceId == null) {
                        throw new IllegalStateException("Committed idempotency key without a result: " + key);
                    }
                    return (Claim) new AlreadyCompleted(resourceId, rs.getString("request_fingerprint"));
                })
                .single();
    }

    @Override
    public void complete(TerminalId terminalId, String key, UUID resourceId) {
        jdbc.sql("""
                        UPDATE idempotency_key
                           SET resource_id = :resource,
                               order_id    = (SELECT id FROM customer_order WHERE id = :resource)
                         WHERE terminal_id = :terminal AND idempotency_key = :key
                        """)
                .param("resource", resourceId)
                .param("terminal", terminalId.value())
                .param("key", key)
                .update();
    }
}
