package com.fleet.storefront.adapter.out.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One dedicated relay thread per destination, fully decoupled from request threads <em>and from each
 * other</em>: a slow or unreachable HQ can never delay kitchen tickets. Stops cleanly on shutdown; an
 * in-flight delivery that is cut short is redelivered after its lease expires.
 */
@Component
@ConditionalOnProperty(name = "storefront.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;
    private final Duration pollInterval;
    private final List<ScheduledExecutorService> workers = new ArrayList<>();
    private volatile boolean running;

    OutboxRelayScheduler(OutboxRelay relay, @Value("${storefront.outbox.poll-interval}") Duration pollInterval) {
        this.relay = relay;
        this.pollInterval = pollInterval;
    }

    @Override
    public synchronized void start() {
        for (String destination : relay.destinations()) {
            ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "outbox-relay-" + destination.toLowerCase(Locale.ROOT)));
            worker.scheduleWithFixedDelay(() -> poll(destination), 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
            workers.add(worker);
        }
        running = true;
        log.info("Outbox relay started for {}", relay.destinations());
    }

    private void poll(String destination) {
        try {
            // Keep draining while there is backlog (e.g. after a restart), then wait for the next tick.
            while (running && relay.drain(destination) > 0) {
                Thread.onSpinWait();
            }
        } catch (RuntimeException e) {
            // Database unavailable etc.: never let the periodic task die; try again next tick.
            log.warn("Outbox relay for {} could not poll: {}", destination, e.toString());
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        workers.forEach(ScheduledExecutorService::shutdown);
        for (ScheduledExecutorService worker : workers) {
            try {
                if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                    worker.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                worker.shutdownNow();
            }
        }
        workers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
