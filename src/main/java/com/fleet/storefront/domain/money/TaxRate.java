package com.fleet.storefront.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A tax rate expressed as a percentage (e.g. {@code 8.25} for 8.25 %), tax-exclusive.
 *
 * <p>Tax is computed per order line and rounded half-even (banker's rounding) to the currency's
 * minor unit, which avoids the systematic upward bias of half-up across many lines.
 */
public record TaxRate(BigDecimal percent) {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public TaxRate {
        Objects.requireNonNull(percent, "percent");
        if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("Tax rate must be within 0..100 %: " + percent);
        }
        percent = percent.stripTrailingZeros();
    }

    public static TaxRate of(String percent) {
        return new TaxRate(new BigDecimal(percent));
    }

    public static TaxRate zero() {
        return new TaxRate(BigDecimal.ZERO);
    }

    public Money taxOn(Money net) {
        BigDecimal raw = net.amount().multiply(percent).divide(HUNDRED);
        return Money.rounded(raw, net.currency(), ROUNDING);
    }
}
