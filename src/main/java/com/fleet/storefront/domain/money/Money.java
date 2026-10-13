package com.fleet.storefront.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable monetary amount bound to a currency.
 *
 * <ul>
 *   <li>Backed by {@link BigDecimal}; there is deliberately no {@code double} factory or accessor.</li>
 *   <li>The scale is always the currency's minor-unit scale (USD 2, JPY 0, BHD 3). Constructing an
 *       amount with more precision than the currency allows fails instead of silently rounding —
 *       rounding only happens through explicit operations that name a {@link RoundingMode}.</li>
 *   <li>Arithmetic between different currencies throws {@link CurrencyMismatchException}.</li>
 * </ul>
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        int scale = minorUnits(currency);
        try {
            amount = amount.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount " + amount.toPlainString() + " has more precision than " + currency + " allows (" + scale + ")");
        }
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /** Builds an amount from an arbitrary-precision intermediate result, rounding explicitly. */
    public static Money rounded(BigDecimal amount, Currency currency, RoundingMode mode) {
        return new Money(amount.setScale(minorUnits(currency), mode), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money times(int multiplier) {
        return new Money(amount.multiply(BigDecimal.valueOf(multiplier)), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isSameCurrency(Money other) {
        return currency.equals(other.currency);
    }

    public void requireSameCurrency(Money other) {
        if (!isSameCurrency(other)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }

    private static int minorUnits(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException("Currency " + currency + " is not a tender currency");
        }
        return digits;
    }
}
