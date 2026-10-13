package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.ReportsQuery;
import com.fleet.storefront.application.port.out.FiscalSequence;
import com.fleet.storefront.application.port.out.OrderRepository;
import com.fleet.storefront.application.port.out.ReportStore;
import com.fleet.storefront.application.readmodel.DailyReport;
import com.fleet.storefront.application.readmodel.FiscalChainReport;
import com.fleet.storefront.domain.fiscal.FiscalChain;
import com.fleet.storefront.domain.fiscal.FiscalCounterState;
import com.fleet.storefront.domain.fiscal.FiscalSeries;
import com.fleet.storefront.domain.order.OrderNumber;
import com.fleet.storefront.domain.refund.RefundNumber;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;

public final class ReportService implements ReportsQuery {

    private final ReportStore reports;
    private final OrderRepository orders;
    private final FiscalSequence fiscalSequence;
    private final BranchContext branch;
    private final Clock clock;

    public ReportService(ReportStore reports, OrderRepository orders, FiscalSequence fiscalSequence,
                         BranchContext branch, Clock clock) {
        this.reports = reports;
        this.orders = orders;
        this.fiscalSequence = fiscalSequence;
        this.branch = branch;
        this.clock = clock;
    }

    @Override
    public DailyReport daily(Optional<LocalDate> businessDate) {
        return reports.daily(branch.branchCode(), date(businessDate), branch.zone(), branch.currency());
    }

    @Override
    public FiscalChainReport verifyFiscalChain(Optional<LocalDate> businessDate) {
        LocalDate date = date(businessDate);
        String code = branch.branchCode();
        return new FiscalChainReport(date, List.of(
                verify(FiscalSeries.ORD, orders.issuedOn(code, date),
                        o -> new Doc(o.number().sequence(), o.number().value(), o.canonicalForm(), o.fiscalHash()),
                        fiscalSequence.state(FiscalSeries.ORD, code, date),
                        seq -> new OrderNumber(code, date, seq).value()),
                verify(FiscalSeries.REF, orders.refundsIssuedOn(code, date),
                        r -> new Doc(r.number().sequence(), r.number().value(), r.canonicalForm(), r.fiscalHash()),
                        fiscalSequence.state(FiscalSeries.REF, code, date),
                        seq -> new RefundNumber(code, date, seq).value())));
    }

    private LocalDate date(Optional<LocalDate> businessDate) {
        return businessDate.orElseGet(() -> LocalDate.ofInstant(clock.instant(), branch.zone()));
    }

    /**
     * Recomputes the chain in number order and cross-checks it against the counter row:
     * <ul>
     *   <li>numbers must run 1..n without gaps, and n must equal the counter (no deleted documents);</li>
     *   <li>from the counter's first chained number on, every document must carry a matching hash (hashes
     *       cannot be stripped to pass as pre-chaining "legacy" documents);</li>
     *   <li>the last document's hash must equal the counter's chain head (no truncated tail).</li>
     * </ul>
     */
    private static <T> FiscalChainReport.Series verify(FiscalSeries series, List<T> documents, Function<T, Doc> doc,
                                                       Optional<FiscalCounterState> counter,
                                                       LongFunction<String> numberOf) {
        long expectedCount = counter.map(FiscalCounterState::lastValue).orElse(0L);
        long firstHashed = counter.map(FiscalCounterState::firstHashedSequence).filter(Objects::nonNull)
                .orElse(Long.MAX_VALUE);
        String previous = null;
        long expectedSeq = 1;
        for (T d : documents) {
            Doc document = doc.apply(d);
            if (document.sequence() != expectedSeq) {
                return broken(series, documents, numberOf.apply(expectedSeq));
            }
            expectedSeq++;
            if (document.hash() == null) {
                if (document.sequence() >= firstHashed) {
                    return broken(series, documents, document.number());
                }
                continue;
            }
            if (!FiscalChain.link(previous, document.canonical()).equals(document.hash())) {
                return broken(series, documents, document.number());
            }
            previous = document.hash();
        }
        if (documents.size() != expectedCount) {
            return broken(series, documents, numberOf.apply(Math.min(documents.size(), expectedCount) + 1));
        }
        String head = counter.map(FiscalCounterState::lastHash).orElse(null);
        if (head != null && !head.equals(previous)) {
            return broken(series, documents, numberOf.apply(expectedCount));
        }
        return new FiscalChainReport.Series(series.name(), documents.size(), true, null);
    }

    private static FiscalChainReport.Series broken(FiscalSeries series, List<?> documents, String number) {
        return new FiscalChainReport.Series(series.name(), documents.size(), false, number);
    }

    private record Doc(long sequence, String number, String canonical, String hash) {
    }
}
