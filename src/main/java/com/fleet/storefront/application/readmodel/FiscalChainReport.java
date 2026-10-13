package com.fleet.storefront.application.readmodel;

import java.time.LocalDate;
import java.util.List;

/** Result of recomputing the day's hash chains. */
public record FiscalChainReport(LocalDate businessDate, List<Series> series) {

    public FiscalChainReport {
        series = List.copyOf(series);
    }

    /**
     * @param documents         documents of the series that day (unsigned legacy documents included)
     * @param firstBrokenNumber first document whose stored hash does not match the recomputed chain
     */
    public record Series(String series, int documents, boolean valid, String firstBrokenNumber) {
    }
}
