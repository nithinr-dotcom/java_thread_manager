package com.fleet.storefront.adapter.out.persistence;

import com.fleet.storefront.application.port.out.ReportStore;
import com.fleet.storefront.application.readmodel.DailyReport;
import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;

/**
 * End-of-day aggregates. Sums are taken over stored NUMERIC values in SQL (exact), then wrapped in
 * {@link Money}. Orders and refunds belong to their fiscal business date; payments to the local day in
 * which they were received.
 */
@Repository
class JdbcReportStore implements ReportStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;

    JdbcReportStore(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.snapshot = new TransactionTemplate(transactions);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }

    /** All aggregates from one REPEATABLE READ snapshot, so totals and payments agree under live traffic. */
    @Override
    public DailyReport daily(String branchCode, LocalDate businessDate, ZoneId zone, Currency currency) {
        return snapshot.execute(status -> compute(branchCode, businessDate, zone, currency));
    }

    private DailyReport compute(String branchCode, LocalDate businessDate, ZoneId zone, Currency currency) {
        record OrderTotals(DailyReport.Orders orders, DailyReport.Sales sales) {
        }
        OrderTotals totals = jdbc.sql("""
                        SELECT count(*)                                                      AS issued,
                               count(*) FILTER (WHERE status = 'CANCELLED')                  AS cancelled,
                               (array_agg(order_number ORDER BY sequence_no))[1]             AS first_number,
                               (array_agg(order_number ORDER BY sequence_no DESC))[1]        AS last_number,
                               COALESCE(sum(subtotal)    FILTER (WHERE status <> 'CANCELLED'), 0) AS subtotal,
                               COALESCE(sum(tax_total)   FILTER (WHERE status <> 'CANCELLED'), 0) AS tax_total,
                               COALESCE(sum(grand_total) FILTER (WHERE status <> 'CANCELLED'), 0) AS grand_total
                          FROM customer_order
                         WHERE branch_code = :branch AND business_date = :date
                        """)
                .param("branch", branchCode)
                .param("date", businessDate)
                .query((rs, n) -> new OrderTotals(
                        new DailyReport.Orders(rs.getLong("issued"), rs.getLong("cancelled"),
                                rs.getString("first_number"), rs.getString("last_number")),
                        new DailyReport.Sales(money(rs.getBigDecimal("subtotal"), currency),
                                money(rs.getBigDecimal("tax_total"), currency),
                                money(rs.getBigDecimal("grand_total"), currency))))
                .single();

        List<DailyReport.TaxLine> taxes = jdbc.sql("""
                        SELECT ol.tax_rate_percent, sum(ol.unit_price * ol.quantity) AS net, sum(ol.line_tax) AS tax
                          FROM order_line ol
                          JOIN customer_order o ON o.id = ol.order_id
                         WHERE o.branch_code = :branch AND o.business_date = :date AND o.status <> 'CANCELLED'
                         GROUP BY ol.tax_rate_percent
                         ORDER BY ol.tax_rate_percent
                        """)
                .param("branch", branchCode)
                .param("date", businessDate)
                .query((rs, n) -> new DailyReport.TaxLine(new TaxRate(rs.getBigDecimal("tax_rate_percent")),
                        money(rs.getBigDecimal("net"), currency), money(rs.getBigDecimal("tax"), currency)))
                .list();

        List<DailyReport.PaymentLine> payments = jdbc.sql("""
                        SELECT p.method, count(*) AS count, sum(p.amount) AS amount
                          FROM payment p
                          JOIN customer_order o ON o.id = p.order_id
                         WHERE o.branch_code = :branch AND p.received_at >= :from AND p.received_at < :to
                           AND p.voided_at IS NULL
                         GROUP BY p.method
                         ORDER BY p.method
                        """)
                .param("branch", branchCode)
                .param("from", Timestamp.from(businessDate.atStartOfDay(zone).toInstant()))
                .param("to", Timestamp.from(businessDate.plusDays(1).atStartOfDay(zone).toInstant()))
                .query((rs, n) -> new DailyReport.PaymentLine(rs.getString("method"), rs.getLong("count"),
                        money(rs.getBigDecimal("amount"), currency)))
                .list();

        DailyReport.Refunds refunds = jdbc.sql("""
                        SELECT count(*) AS count, COALESCE(sum(grand_total), 0) AS total
                          FROM refund
                         WHERE branch_code = :branch AND business_date = :date
                        """)
                .param("branch", branchCode)
                .param("date", businessDate)
                .query((rs, n) -> new DailyReport.Refunds(rs.getLong("count"), money(rs.getBigDecimal("total"), currency)))
                .single();

        Money unpaid = jdbc.sql("""
                        SELECT COALESCE(sum(o.grand_total - COALESCE(paid.amount, 0)), 0) AS unpaid
                          FROM customer_order o
                          LEFT JOIN LATERAL (SELECT sum(p.amount) AS amount FROM payment p
                                         WHERE p.order_id = o.id AND p.voided_at IS NULL) paid
                                 ON TRUE
                         WHERE o.branch_code = :branch AND o.business_date = :date AND o.status = 'PLACED'
                        """)
                .param("branch", branchCode)
                .param("date", businessDate)
                .query((rs, n) -> money(rs.getBigDecimal("unpaid"), currency))
                .single();

        return new DailyReport(businessDate, branchCode, currency, totals.orders(), totals.sales(), taxes, payments,
                refunds, unpaid);
    }

    private static Money money(BigDecimal amount, Currency currency) {
        return Money.of(amount == null ? BigDecimal.ZERO : amount, currency);
    }
}
