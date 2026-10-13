package com.fleet.storefront.application.port.in;

import com.fleet.storefront.application.readmodel.DailyReport;
import com.fleet.storefront.application.readmodel.FiscalChainReport;

import java.time.LocalDate;
import java.util.Optional;

/** End-of-day reporting and fiscal integrity checks; the date defaults to today in the branch zone. */
public interface ReportsQuery {

    DailyReport daily(Optional<LocalDate> businessDate);

    FiscalChainReport verifyFiscalChain(Optional<LocalDate> businessDate);
}
