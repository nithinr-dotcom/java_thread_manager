package com.fleet.storefront.application.port.out;

import com.fleet.storefront.application.readmodel.DailyReport;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;

/** Aggregations for end-of-day reporting, computed in SQL over the fiscal records. */
public interface ReportStore {

    DailyReport daily(String branchCode, LocalDate businessDate, ZoneId zone, Currency currency);
}
