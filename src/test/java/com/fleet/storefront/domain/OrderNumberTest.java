package com.fleet.storefront.domain;

import com.fleet.storefront.domain.order.OrderNumber;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderNumberTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);

    @Test
    void formatsAsBranchDateAndZeroPaddedSequence() {
        assertThat(new OrderNumber("HOSP01", DAY, 1).value()).isEqualTo("ORD-HOSP01-20261013-0001");
        assertThat(new OrderNumber("HOSP01", DAY, 12345).value()).isEqualTo("ORD-HOSP01-20261013-12345");
    }

    @Test
    void parsesItsOwnFormat() {
        OrderNumber number = new OrderNumber("AIR7", DAY, 42);
        assertThat(OrderNumber.parse(number.value())).isEqualTo(number);
    }

    @Test
    void ordersBySequenceWithinADay() {
        assertThat(new OrderNumber("HOSP01", DAY, 9)).isLessThan(new OrderNumber("HOSP01", DAY, 10));
        assertThat(new OrderNumber("HOSP01", DAY, 9999)).isLessThan(new OrderNumber("HOSP01", DAY.plusDays(1), 1));
    }

    @Test
    void rejectsInvalidParts() {
        assertThatThrownBy(() -> new OrderNumber("hosp-01", DAY, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderNumber("HOSP01", DAY, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderNumber.parse("ORD-HOSP01-2026-1")).isInstanceOf(IllegalArgumentException.class);
    }
}
