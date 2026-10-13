package com.fleet.storefront.application.readmodel;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * @param pending   not yet delivered and still being retried (excludes dead letters)
 * @param failing   pending events whose last delivery attempt failed
 * @param dead      events that exhausted their attempts or failed permanently
 * @param lastError the most recent failure of a pending event
 */
public record OutboxDestinationStatus(String destination, long pending, long published, long failing, long dead,
                                      Optional<Instant> oldestPendingAt, Optional<String> lastError) {

    public OutboxDestinationStatus {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(oldestPendingAt, "oldestPendingAt");
        Objects.requireNonNull(lastError, "lastError");
    }
}
