package com.fleet.storefront.domain;

import com.fleet.storefront.domain.shared.UuidV7;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void hasVersion7AndIetfVariant() {
        UUID id = UuidV7.next();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheTimestampAndSortsByTime() {
        Random random = new Random(1);
        Instant earlier = Instant.parse("2026-10-13T10:00:00.000Z");
        Instant later = earlier.plusMillis(1);
        UUID a = UuidV7.at(earlier, random);
        UUID b = UuidV7.at(later, random);
        assertThat(UuidV7.timestampOf(a)).isEqualTo(earlier);
        assertThat(a.toString()).isLessThan(b.toString());
    }

    @Test
    void isUniqueAcrossManyGenerations() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            ids.add(UuidV7.next());
        }
        assertThat(ids).hasSize(100_000);
    }
}
