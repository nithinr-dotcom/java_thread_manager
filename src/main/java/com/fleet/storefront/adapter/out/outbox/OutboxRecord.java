package com.fleet.storefront.adapter.out.outbox;

import java.time.Instant;
import java.util.UUID;

/** A claimed outbox row. {@code attempts} already includes the current delivery attempt. */
public record OutboxRecord(UUID id, String destination, String eventType, UUID aggregateId, String payload,
                           int attempts, Instant createdAt) {
}
