package com.fleet.storefront.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("storefront")
public record StorefrontProperties(Branch branch, Checkout checkout, Outbox outbox, Hq hq, Retention retention) {

    /**
     * @param code     fiscal branch code used in document numbers
     * @param currency ISO-4217 currency the branch trades in
     * @param zone     time zone defining the business day for fiscal numbering
     * @param nodeId   identity of this edge node, stamped on every record for HQ sync
     */
    public record Branch(String code, String currency, String zone, String nodeId) {
    }

    /** @param lockTimeout max wait for any row lock in checkout before failing fast with a retryable error */
    public record Checkout(Duration lockTimeout) {
    }

    /**
     * @param maxAttempts   delivery attempts before an event becomes a dead letter (local destinations)
     * @param hqMaxAttempts the same for HQ; 0 = never give up on transient failures (WAN outages)
     */
    public record Outbox(boolean relayEnabled, Duration pollInterval, int batchSize, Duration lease,
                         Duration initialBackoff, Duration maxBackoff, int maxAttempts, int hqMaxAttempts) {
    }

    /**
     * @param baseUrl Central HQ sync endpoint; when blank HQ events stay queued in the outbox (offline mode)
     * @param apiKey  bearer credential the node presents to HQ
     */
    public record Hq(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
    }

    /**
     * @param idempotencyKeys how long a retry is recognised as a replay (default 7 days)
     * @param outboxEvents    how long delivered events are kept for diagnosis (default 7 days)
     */
    public record Retention(@DefaultValue("P7D") Duration idempotencyKeys, @DefaultValue("P7D") Duration outboxEvents,
                            @DefaultValue("1000") int batchSize) {
    }
}
