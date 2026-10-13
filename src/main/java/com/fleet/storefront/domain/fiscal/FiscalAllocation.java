package com.fleet.storefront.domain.fiscal;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of allocating the next fiscal number.
 *
 * @param sequence     gap-free number within the series/branch/day, starting at 1
 * @param issuedAt     document timestamp; never earlier than the previous document's (monotonic even if
 *                     the wall clock steps back), so number order and time order always agree
 * @param previousHash hash of the previous document of the chain, or {@code null} for the first one
 */
public record FiscalAllocation(long sequence, Instant issuedAt, String previousHash) {

    public FiscalAllocation {
        if (sequence < 1) {
            throw new IllegalArgumentException("Sequence must start at 1: " + sequence);
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
    }
}
