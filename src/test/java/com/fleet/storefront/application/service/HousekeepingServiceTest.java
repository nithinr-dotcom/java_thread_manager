package com.fleet.storefront.application.service;

import com.fleet.storefront.application.port.in.HousekeepingUseCase.PurgeResult;
import com.fleet.storefront.application.port.out.Housekeeping;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HousekeepingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    @Test
    void purgesInBatchesUntilABatchComesBackShort_usingTheRetentionCutoffs() {
        List<Instant> keyCutoffs = new ArrayList<>();
        List<Integer> keyBatches = new ArrayList<>(List.of(10, 10, 3));
        Housekeeping fake = new Housekeeping() {
            @Override
            public int deleteIdempotencyKeysBefore(Instant before, int batchSize) {
                keyCutoffs.add(before);
                return keyBatches.removeFirst();
            }

            @Override
            public int deletePublishedOutboxEventsBefore(Instant before, int batchSize) {
                assertThat(before).isEqualTo(NOW.minus(Duration.ofDays(2)));
                return 0;
            }
        };
        HousekeepingService service = new HousekeepingService(fake, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(7), Duration.ofDays(2), 10);

        assertThat(service.purgeExpired()).isEqualTo(new PurgeResult(23, 0));
        assertThat(keyCutoffs).containsOnly(NOW.minus(Duration.ofDays(7)));
    }

    @Test
    void refusesARetentionThatWouldBreakRetrySafety() {
        assertThatThrownBy(() -> new HousekeepingService(null, Clock.systemUTC(), Duration.ofMinutes(5),
                Duration.ofDays(1), 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
