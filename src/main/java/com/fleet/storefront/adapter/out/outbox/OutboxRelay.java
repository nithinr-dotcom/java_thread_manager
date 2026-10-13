package com.fleet.storefront.adapter.out.outbox;

import com.fleet.storefront.application.port.out.Outbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Drains due outbox records to their dispatchers. Failures never propagate back to checkout (which
 * has long committed); they are recorded and retried with capped exponential backoff until
 * {@code max-attempts}, after which the event becomes a dead letter for an operator to inspect and
 * re-queue — an event is never silently dropped. Permanent failures
 * ({@link NonRetryableDispatchException}) are dead-lettered at once. Destinations without a registered
 * dispatcher (e.g. HQ while offline) simply accumulate until one is available.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final List<OutboxDispatcher> dispatchers;
    private final MeterRegistry meters;
    private final int batchSize;
    private final Duration lease;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;
    private final int hqMaxAttempts;

    OutboxRelay(OutboxStore store, List<OutboxDispatcher> dispatchers, MeterRegistry meters,
                @Value("${storefront.outbox.batch-size}") int batchSize,
                @Value("${storefront.outbox.lease}") Duration lease,
                @Value("${storefront.outbox.initial-backoff}") Duration initialBackoff,
                @Value("${storefront.outbox.max-backoff}") Duration maxBackoff,
                @Value("${storefront.outbox.max-attempts}") int maxAttempts,
                @Value("${storefront.outbox.hq-max-attempts:0}") int hqMaxAttempts) {
        this.store = store;
        this.dispatchers = List.copyOf(dispatchers);
        this.meters = meters;
        this.batchSize = batchSize;
        this.lease = lease;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.maxAttempts = maxAttempts;
        this.hqMaxAttempts = hqMaxAttempts;
    }

    /**
     * Attempt budget per destination; 0 = unlimited. HQ is unlimited by default: a WAN outage of hours or
     * days is the normal offline case and must never turn a day of orders into dead letters. Permanent
     * failures are still dead-lettered at once.
     */
    public int maxAttempts(String destination) {
        return Outbox.Destination.HQ.name().equals(destination) ? hqMaxAttempts : maxAttempts;
    }

    /** Destinations that have a dispatcher on this node. */
    public List<String> destinations() {
        return dispatchers.stream().map(OutboxDispatcher::destination).toList();
    }

    /** Delivers one batch per destination; returns the number of records attempted. */
    public int drainOnce() {
        int attempted = 0;
        for (OutboxDispatcher dispatcher : dispatchers) {
            attempted += drain(dispatcher);
        }
        return attempted;
    }

    /** Delivers one batch of {@code destination}; returns the number of records attempted. */
    public int drain(String destination) {
        return dispatcher(destination).map(this::drain).orElse(0);
    }

    private Optional<OutboxDispatcher> dispatcher(String destination) {
        return dispatchers.stream().filter(d -> d.destination().equals(destination)).findFirst();
    }

    private int drain(OutboxDispatcher dispatcher) {
        List<OutboxRecord> batch = store.claimDue(dispatcher.destination(), batchSize, lease);
        for (OutboxRecord record : batch) {
            try {
                dispatcher.dispatch(record);
                store.markPublished(record.id());
                count(record, "delivered");
            } catch (NonRetryableDispatchException e) {
                log.error("Delivery of {} {} to {} failed permanently, dead-lettered: {}", record.eventType(),
                        record.id(), record.destination(), e.toString());
                store.markDead(record.id(), e.toString());
                count(record, "dead");
            } catch (Exception e) {
                int budget = maxAttempts(record.destination());
                if (budget > 0 && record.attempts() >= budget) {
                    log.error("Delivery of {} {} to {} failed {} times, dead-lettered: {}", record.eventType(),
                            record.id(), record.destination(), record.attempts(), e.toString());
                    store.markDead(record.id(), e.toString());
                    count(record, "dead");
                } else {
                    Duration retryIn = backoff(record.attempts());
                    log.warn("Delivery of {} {} to {} failed (attempt {}), retrying in {}: {}", record.eventType(),
                            record.id(), record.destination(), record.attempts(), retryIn, e.toString());
                    store.markFailed(record.id(), e.toString(), retryIn);
                    count(record, "failed");
                }
            }
        }
        return batch.size();
    }

    private void count(OutboxRecord record, String result) {
        Counter.builder("storefront.outbox.deliveries")
                .description("Outbox delivery attempts by destination and result")
                .tag("destination", record.destination())
                .tag("result", result)
                .register(meters)
                .increment();
    }

    Duration backoff(int attempts) {
        long factor = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration delay = initialBackoff.multipliedBy(factor);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
