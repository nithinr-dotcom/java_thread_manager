package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.HousekeepingUseCase;
import com.fleet.storefront.application.port.out.Housekeeping;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.IntSupplier;

/**
 * Retention: idempotency keys (a retry older than the retention is treated as a new request) and
 * delivered outbox events. Small batches, each its own statement, so purging never holds long locks or
 * competes with checkouts.
 */
public final class HousekeepingService implements HousekeepingUseCase {

    private final Housekeeping housekeeping;
    private final Clock clock;
    private final Duration idempotencyRetention;
    private final Duration outboxRetention;
    private final int batchSize;

    public HousekeepingService(Housekeeping housekeeping, Clock clock, Duration idempotencyRetention,
                               Duration outboxRetention, int batchSize) {
        if (idempotencyRetention.compareTo(Duration.ofHours(1)) < 0) {
            throw new IllegalArgumentException("Idempotency keys must be kept at least one hour");
        }
        if (outboxRetention.isNegative()) {
            throw new IllegalArgumentException("Outbox retention must not be negative");
        }
        this.housekeeping = housekeeping;
        this.clock = clock;
        this.idempotencyRetention = idempotencyRetention;
        this.outboxRetention = outboxRetention;
        this.batchSize = batchSize;
    }

    @Override
    public PurgeResult purgeExpired() {
        Instant now = clock.instant();
        int keys = drain(() -> housekeeping.deleteIdempotencyKeysBefore(now.minus(idempotencyRetention), batchSize));
        int events = drain(() -> housekeeping.deletePublishedOutboxEventsBefore(now.minus(outboxRetention), batchSize));
        return new PurgeResult(keys, events);
    }

    private int drain(IntSupplier batch) {
        int total = 0;
        int deleted;
        do {
            deleted = batch.getAsInt();
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
