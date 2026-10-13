package com.fleet.storefront.application.port.in;

/** Deletes data past its retention (old idempotency keys, delivered outbox events), in small batches. */
public interface HousekeepingUseCase {

    PurgeResult purgeExpired();

    record PurgeResult(int idempotencyKeys, int outboxEvents) {
    }
}
