package com.fleet.storefront.adapter.out.persistence;

import com.fleet.storefront.application.port.out.FiscalSequence;
import com.fleet.storefront.domain.fiscal.FiscalAllocation;
import com.fleet.storefront.domain.fiscal.FiscalCounterState;
import com.fleet.storefront.domain.fiscal.FiscalSeries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Transactional counter row per series, branch and day. The upsert both creates the day's counter and
 * increments it atomically; the row lock is held until the surrounding transaction commits, which is
 * what makes the numbers gap-free (rollback undoes the increment) and commit-ordered. The same row keeps
 * the latest document time ({@code GREATEST}: never backwards) and the head of the hash chain.
 */
@Repository
class JdbcFiscalSequence implements FiscalSequence {

    private final JdbcClient jdbc;

    JdbcFiscalSequence(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public FiscalAllocation next(FiscalSeries series, String branchCode, LocalDate businessDate, Instant now) {
        return jdbc.sql("""
                        INSERT INTO fiscal_sequence (branch_code, series, business_date, last_value, last_issued_at)
                        VALUES (:branch, :series, :date, 1, :now)
                        ON CONFLICT (branch_code, series, business_date)
                        DO UPDATE SET last_value     = fiscal_sequence.last_value + 1,
                                      last_issued_at = GREATEST(fiscal_sequence.last_issued_at, EXCLUDED.last_issued_at)
                        RETURNING last_value, last_issued_at, last_hash
                        """)
                .param("branch", branchCode)
                .param("series", series.name())
                .param("date", businessDate)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new FiscalAllocation(rs.getLong("last_value"),
                        rs.getTimestamp("last_issued_at").toInstant(), rs.getString("last_hash")))
                .single();
    }

    @Override
    public void recordHash(FiscalSeries series, String branchCode, LocalDate businessDate, long sequence, String hash) {
        int updated = jdbc.sql("""
                        UPDATE fiscal_sequence
                           SET last_hash = :hash, first_hashed_seq = COALESCE(first_hashed_seq, :seq)
                         WHERE branch_code = :branch AND series = :series AND business_date = :date
                        """)
                .param("hash", hash)
                .param("seq", sequence)
                .param("branch", branchCode)
                .param("series", series.name())
                .param("date", businessDate)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("No fiscal counter for " + series + "/" + branchCode + "/" + businessDate);
        }
    }

    @Override
    public Optional<FiscalCounterState> state(FiscalSeries series, String branchCode, LocalDate businessDate) {
        return jdbc.sql("""
                        SELECT last_value, last_hash, first_hashed_seq FROM fiscal_sequence
                         WHERE branch_code = :branch AND series = :series AND business_date = :date
                        """)
                .param("branch", branchCode)
                .param("series", series.name())
                .param("date", businessDate)
                .query((rs, n) -> new FiscalCounterState(rs.getLong("last_value"), rs.getString("last_hash"),
                        rs.getObject("first_hashed_seq", Long.class)))
                .optional();
    }
}
