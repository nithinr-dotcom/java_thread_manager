package com.fleet.storefront.domain.fiscal;

/**
 * What the counter row says about a series/branch/day: how many documents were issued, the hash of the
 * last one, and the first sequence number that was hash-chained (all later ones must be).
 */
public record FiscalCounterState(long lastValue, String lastHash, Long firstHashedSequence) {
}
