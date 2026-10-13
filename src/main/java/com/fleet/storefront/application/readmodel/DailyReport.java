package com.fleet.storefront.application.readmodel;

import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

/** End-of-day (Z) report of one branch and business day. Cancelled orders are excluded from sales. */
public record DailyReport(LocalDate businessDate, String branchCode, Currency currency, Orders orders, Sales sales,
                          List<TaxLine> taxByRate, List<PaymentLine> payments, Refunds refunds, Money unpaidTotal) {

    public DailyReport {
        taxByRate = List.copyOf(taxByRate);
        payments = List.copyOf(payments);
    }

    public record Orders(long issued, long cancelled, String firstNumber, String lastNumber) {
    }

    public record Sales(Money subtotal, Money taxTotal, Money total) {
    }

    public record TaxLine(TaxRate rate, Money net, Money tax) {
    }

    public record PaymentLine(String method, long count, Money amount) {
    }

    public record Refunds(long count, Money total) {
    }
}
