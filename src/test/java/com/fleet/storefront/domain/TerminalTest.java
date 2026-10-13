package com.fleet.storefront.domain;

import com.fleet.storefront.domain.refund.RefundNumber;
import com.fleet.storefront.domain.shared.Sha256;
import com.fleet.storefront.domain.terminal.ApiKeys;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TerminalTest {

    @Test
    void apiKeysAreUniqueHighEntropyAndOnlyTheirHashIsComparable() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(ApiKeys.generate());
        }
        assertThat(keys).hasSize(1000).allMatch(k -> k.matches("sf_[A-Za-z0-9_-]{43}"));
        String key = keys.iterator().next();
        assertThat(ApiKeys.hash(key)).isEqualTo(Sha256.hex(key)).hasSize(64).isNotEqualTo(key);
    }

    @Test
    void refundNumbersFormatAndParseLikeOrderNumbers() {
        RefundNumber n = new RefundNumber("HOSP01", LocalDate.of(2026, 10, 13), 12);
        assertThat(n.value()).isEqualTo("REF-HOSP01-20261013-0012");
        assertThat(RefundNumber.parse(n.value())).isEqualTo(n);
        assertThatThrownBy(() -> RefundNumber.parse("ORD-HOSP01-20261013-0012"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
