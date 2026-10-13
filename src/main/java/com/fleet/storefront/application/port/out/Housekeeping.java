package com.fleet.storefront.application.port.out;

import java.time.Instant;

/** Batched retention deletes; each call is its own short statement, never inside checkout. */
public interface Housekeeping {

    /** Deletes up to {@code batchSize} idempotency keys created before {@code before}; returns the count. */
    int deleteIdempotencyKeysBefore(Instant before, int batchSize);

    /** Deletes up to {@code batchSize} delivered outbox events published before {@code before}; returns the count. */
    int deletePublishedOutboxEventsBefore(Instant before, int batchSize);
}
