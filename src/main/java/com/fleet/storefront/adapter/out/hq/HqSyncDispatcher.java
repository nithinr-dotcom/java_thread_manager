package com.fleet.storefront.adapter.out.hq;

import com.fleet.storefront.adapter.out.outbox.NonRetryableDispatchException;
import com.fleet.storefront.adapter.out.outbox.OutboxDispatcher;
import com.fleet.storefront.adapter.out.outbox.OutboxRecord;
import com.fleet.storefront.application.port.out.Outbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Set;

/**
 * Pushes HQ-bound outbox events to Central HQ. Only active when {@code storefront.hq.base-url} is set;
 * otherwise HQ events wait in the outbox, which is exactly the offline behaviour we want. A failed
 * push (WAN down, timeout, 5xx) is retried by the relay with backoff; a request HQ rejects as malformed
 * is dead-lettered for an operator.
 *
 * <p>HQ deduplicates on {@code Idempotency-Key = outbox event id}. Events are complete, immutable facts
 * keyed by UUIDv7 ids, so HQ must not rely on arrival order (retries reorder them).
 */
@Component
@ConditionalOnExpression("!'${storefront.hq.base-url:}'.isBlank()")
class HqSyncDispatcher implements OutboxDispatcher {

    private static final Set<HttpStatus> PERMANENT =
            Set.of(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.PAYLOAD_TOO_LARGE);

    private final HqHttpClient hq;

    HqSyncDispatcher(HqHttpClient hq) {
        this.hq = hq;
    }

    @Override
    public String destination() {
        return Outbox.Destination.HQ.name();
    }

    @Override
    public void dispatch(OutboxRecord record) {
        try {
            hq.http().post()
                    .uri("/api/v1/sync/events/{type}", record.eventType())
                    .header("Idempotency-Key", record.id().toString())
                    .header("X-Event-Created-At", record.createdAt().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(record.payload())
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (PERMANENT.contains(HttpStatus.resolve(e.getStatusCode().value()))) {
                throw new NonRetryableDispatchException("HQ rejected " + record.eventType() + ": " + e.getStatusCode(), e);
            }
            throw e;
        }
    }
}
