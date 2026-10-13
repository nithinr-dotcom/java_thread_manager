package com.fleet.storefront.adapter.in.scheduling;

import com.fleet.storefront.application.port.in.SyncCatalogUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Pulls catalog changes from Central HQ while it is reachable. Inactive when no HQ URL is configured. */
@Component
@EnableScheduling
@ConditionalOnExpression("!'${storefront.hq.base-url:}'.isBlank() and '${storefront.hq.catalog-pull-enabled:true}' == 'true'")
class CatalogSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncScheduler.class);

    private final SyncCatalogUseCase sync;

    CatalogSyncScheduler(SyncCatalogUseCase sync) {
        this.sync = sync;
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${storefront.hq.catalog-pull-interval:PT5M}")
    void pull() {
        try {
            SyncCatalogUseCase.SyncResult result = sync.pullFromHq();
            if (result.applied() + result.skipped() > 0) {
                log.info("HQ catalog sync: {} applied, {} skipped", result.applied(), result.skipped());
            }
        } catch (RuntimeException e) {
            // Offline is the normal case for an edge node: keep selling on the local catalog.
            log.info("HQ catalog sync unavailable, will retry: {}", e.toString());
        }
    }
}
