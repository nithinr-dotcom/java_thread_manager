package com.fleet.storefront.domain;

import com.fleet.storefront.domain.money.CurrencyMismatchException;
import com.fleet.storefront.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalisesToCurrencyScaleSoEqualAmountsAreEqual() {
        assertThat(Money.of("1.5", "USD")).isEqualTo(Money.of("1.50", "USD"));
        assertThat(Money.of("1.5", "USD").amount().scale()).isEqualTo(2);
        assertThat(Money.of("100", "JPY").amount().scale()).isZero();
        assertThat(Money.of("1.234", "BHD").amount().scale()).isEqualTo(3);
    }

    @Test
    void rejectsMorePrecisionThanTheCurrencyAllowsInsteadOfSilentlyRounding() {
        assertThatThrownBy(() -> Money.of("0.001", "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1.5", "JPY")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactDecimalArithmeticHasNoFloatingPointDrift() {
        Money tenCents = Money.of("0.10", "USD");
        Money sum = Money.zero(Currency.getInstance("USD"));
        for (int i = 0; i < 10; i++) {
            sum = sum.plus(tenCents);
        }
        assertThat(sum).isEqualTo(Money.of("1.00", "USD")); // 0.1 * 10 in double is 0.9999999999999999
        assertThat(Money.of("0.30", "USD").minus(Money.of("0.10", "USD"))).isEqualTo(Money.of("0.20", "USD"));
        assertThat(Money.of("19.99", "USD").times(3)).isEqualTo(Money.of("59.97", "USD"));
    }

    @Test
    void mixingCurrenciesIsRejected() {
        Money usd = Money.of("1.00", "USD");
        Money eur = Money.of("1.00", "EUR");
        assertThatThrownBy(() -> usd.plus(eur)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> usd.minus(eur)).isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> usd.compareTo(eur)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void roundingOnlyHappensExplicitly() {
        Money rounded = Money.rounded(new BigDecimal("2.345"), Currency.getInstance("USD"), RoundingMode.HALF_EVEN);
        assertThat(rounded).isEqualTo(Money.of("2.34", "USD"));
    }
}
