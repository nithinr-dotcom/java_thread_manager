package com.fleet.storefront.adapter.out.outbox;

import com.fleet.storefront.application.port.out.Outbox;
import com.fleet.storefront.application.port.out.OutboxMonitor;
import com.fleet.storefront.application.readmodel.OutboxDestinationStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Backlog gauges per destination for alerting: pending events, dead letters and the age of the oldest
 * pending event (the delivery lag). One aggregate query serves all gauges, cached for a few seconds so
 * a scrape never runs it more than once.
 */
@Component
class OutboxMetrics implements MeterBinder {

    private static final Duration CACHE = Duration.ofSeconds(5);

    private final OutboxMonitor monitor;
    private final Clock clock = Clock.systemUTC();
    private volatile Snapshot snapshot = new Snapshot(Instant.EPOCH, List.of());

    OutboxMetrics(OutboxMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (Outbox.Destination destination : Outbox.Destination.values()) {
            String name = destination.name();
            gauge(registry, "storefront.outbox.pending", name, "Events awaiting delivery",
                    OutboxDestinationStatus::pending);
            gauge(registry, "storefront.outbox.dead", name, "Dead-lettered events", OutboxDestinationStatus::dead);
            gauge(registry, "storefront.outbox.oldest.pending.age.seconds", name,
                    "Seconds since the oldest undelivered event was created (delivery lag)",
                    s -> s.oldestPendingAt().map(t -> Duration.between(t, clock.instant()).toSeconds()).orElse(0L));
        }
    }

    /** Supplier-of-Number gauges keep floating point out of our code (ArchitectureTest). */
    private void gauge(MeterRegistry registry, String metric, String destination, String description,
                       Function<OutboxDestinationStatus, Long> value) {
        Supplier<Number> reading = () -> status(destination).map(value).orElse(0L);
        Gauge.builder(metric, reading)
                .description(description)
                .tag("destination", destination)
                .register(registry);
    }

    private Optional<OutboxDestinationStatus> status(String destination) {
        Snapshot current = snapshot;
        if (current.takenAt().plus(CACHE).isBefore(clock.instant())) {
            try {
                current = new Snapshot(clock.instant(), monitor.status());
            } catch (RuntimeException e) {
                current = new Snapshot(clock.instant(), current.status());
            }
            snapshot = current;
        }
        return current.status().stream().filter(s -> s.destination().equals(destination)).findFirst();
    }

    private record Snapshot(Instant takenAt, List<OutboxDestinationStatus> status) {
    }
}
