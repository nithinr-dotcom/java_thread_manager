package com.fleet.storefront.adapter.in.scheduling;

import com.fleet.storefront.application.port.in.HousekeepingUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs retention purges off the request path (default hourly). */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "storefront.housekeeping.enabled", havingValue = "true", matchIfMissing = true)
class HousekeepingScheduler {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingScheduler.class);

    private final HousekeepingUseCase housekeeping;

    HousekeepingScheduler(HousekeepingUseCase housekeeping) {
        this.housekeeping = housekeeping;
    }

    @Scheduled(initialDelayString = "${storefront.housekeeping.initial-delay:PT1M}",
            fixedDelayString = "${storefront.housekeeping.interval:PT1H}")
    void purge() {
        try {
            HousekeepingUseCase.PurgeResult result = housekeeping.purgeExpired();
            if (result.idempotencyKeys() + result.outboxEvents() > 0) {
                log.info("Retention purge removed {} idempotency keys and {} delivered outbox events",
                        result.idempotencyKeys(), result.outboxEvents());
            }
        } catch (RuntimeException e) {
            log.warn("Retention purge failed, will retry next interval: {}", e.toString());
        }
    }
}
