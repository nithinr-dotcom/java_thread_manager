package com.fleet.storefront.domain;

import com.fleet.storefront.domain.money.Money;
import com.fleet.storefront.domain.money.TaxRate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxRateTest {

    @Test
    void roundsHalfEvenToTheCurrencyMinorUnit() {
        TaxRate tenPercent = TaxRate.of("10");
        assertThat(tenPercent.taxOn(Money.of("0.25", "USD"))).isEqualTo(Money.of("0.02", "USD")); // 0.025 -> 0.02
        assertThat(tenPercent.taxOn(Money.of("0.35", "USD"))).isEqualTo(Money.of("0.04", "USD")); // 0.035 -> 0.04
    }

    @Test
    void respectsZeroDecimalCurrencies() {
        assertThat(TaxRate.of("8").taxOn(Money.of("105", "JPY"))).isEqualTo(Money.of("8", "JPY")); // 8.4 -> 8
    }

    @Test
    void handlesFractionalRates() {
        assertThat(TaxRate.of("8.25").taxOn(Money.of("11.40", "USD"))).isEqualTo(Money.of("0.94", "USD")); // 0.9405
    }

    @Test
    void rejectsOutOfRangeRates() {
        assertThatThrownBy(() -> TaxRate.of("-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaxRate.of("100.01")).isInstanceOf(IllegalArgumentException.class);
    }
}
