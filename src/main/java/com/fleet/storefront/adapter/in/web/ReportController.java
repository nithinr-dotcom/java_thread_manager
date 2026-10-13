package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.application.port.in.ReportsQuery;
import com.fleet.storefront.application.readmodel.DailyReport;
import com.fleet.storefront.application.readmodel.FiscalChainReport;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

    private final ReportsQuery reports;

    ReportController(ReportsQuery reports) {
        this.reports = reports;
    }

    @GetMapping("/daily")
    DailyReportResponse daily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return DailyReportResponse.from(reports.daily(Optional.ofNullable(date)));
    }

    @GetMapping("/fiscal-chain")
    FiscalChainReport fiscalChain(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reports.verifyFiscalChain(Optional.ofNullable(date));
    }

    record DailyReportResponse(LocalDate businessDate, String branchCode, String currency, DailyReport.Orders orders,
                               Sales sales, List<TaxLine> taxByRate, List<PaymentLine> payments, Refunds refunds,
                               BigDecimal unpaidTotal) {

        record Sales(BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total) {
        }

        record TaxLine(BigDecimal taxRatePercent, BigDecimal net, BigDecimal tax) {
        }

        record PaymentLine(String method, long count, BigDecimal amount) {
        }

        record Refunds(long count, BigDecimal total) {
        }

        static DailyReportResponse from(DailyReport r) {
            return new DailyReportResponse(r.businessDate(), r.branchCode(), r.currency().getCurrencyCode(), r.orders(),
                    new Sales(r.sales().subtotal().amount(), r.sales().taxTotal().amount(), r.sales().total().amount()),
                    r.taxByRate().stream()
                            .map(t -> new TaxLine(t.rate().percent(), t.net().amount(), t.tax().amount()))
                            .toList(),
                    r.payments().stream()
                            .map(p -> new PaymentLine(p.method(), p.count(), p.amount().amount()))
                            .toList(),
                    new Refunds(r.refunds().count(), r.refunds().total().amount()),
                    r.unpaidTotal().amount());
        }
    }
}
