package com.fleet.storefront.application.port.out;

import com.fleet.storefront.domain.fiscal.FiscalAllocation;
import com.fleet.storefront.domain.fiscal.FiscalCounterState;
import com.fleet.storefront.domain.fiscal.FiscalSeries;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Gap-free fiscal numbering, one counter row per series, branch and business day. Both methods must
 * participate in the caller's transaction: a rollback returns the number.
 */
public interface FiscalSequence {

    /**
     * Allocates the next number (starting at 1) and locks the counter until commit.
     *
     * @param now wall-clock time; the returned {@link FiscalAllocation#issuedAt()} is
     *            {@code max(now, previous issuedAt)} so document time never runs backwards
     */
    FiscalAllocation next(FiscalSeries series, String branchCode, LocalDate businessDate, Instant now);

    /** Records the hash of the document {@code sequence} just issued as the head of the chain. */
    void recordHash(FiscalSeries series, String branchCode, LocalDate businessDate, long sequence, String hash);

    /** The counter row of a series/branch/day (read-only, no lock), for integrity verification. */
    Optional<FiscalCounterState> state(FiscalSeries series, String branchCode, LocalDate businessDate);
}
