package com.fleet.storefront.adapter.in.web;

import com.fleet.storefront.application.port.in.KitchenBoardQuery;
import com.fleet.storefront.application.port.in.Limit;
import com.fleet.storefront.application.readmodel.KitchenTicketView;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live KDS streams. Each open stream is a virtual thread polling the board for changes (cheap: one
 * indexed query per second per screen) rather than an in-memory event bus, so it sees every change no
 * matter which process or relay thread made it, and survives nothing it shouldn't. The query window
 * overlaps the previous one and changes are de-duplicated per ticket, so a transaction that commits
 * slightly after its timestamp is never missed.
 */
@Component
class KitchenStreams {

    private static final Logger log = LoggerFactory.getLogger(KitchenStreams.class);
    private static final Duration OVERLAP = Duration.ofSeconds(5);
    private static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private final KitchenBoardQuery board;
    private final Duration pollInterval;
    private final Duration maxStreamAge;
    private final Set<Stream> open = ConcurrentHashMap.newKeySet();

    KitchenStreams(KitchenBoardQuery board,
                   @Value("${storefront.kitchen.stream-poll-interval:1s}") Duration pollInterval,
                   @Value("${storefront.kitchen.stream-max-age:30m}") Duration maxStreamAge) {
        this.board = board;
        this.pollInterval = pollInterval;
        this.maxStreamAge = maxStreamAge;
    }

    SseEmitter open(Optional<String> station) {
        // Streams end after maxStreamAge; clients reconnect (they must anyway, e.g. after a restart).
        SseEmitter emitter = new SseEmitter(maxStreamAge.toMillis() + 10_000);
        Stream stream = new Stream(emitter, station);
        open.add(stream);
        emitter.onCompletion(stream::stop);
        emitter.onTimeout(stream::stop);
        emitter.onError(e -> stream.stop());
        Thread.ofVirtual().name("kds-stream").start(stream::run);
        return emitter;
    }

    int openStreams() {
        return open.size();
    }

    @PreDestroy
    void closeAll() {
        open.forEach(s -> {
            s.stop();
            s.emitter.complete();
        });
    }

    private final class Stream {

        private final SseEmitter emitter;
        private final Optional<String> station;
        private final Map<UUID, Instant> sent = new HashMap<>();
        private volatile boolean running = true;

        Stream(SseEmitter emitter, Optional<String> station) {
            this.emitter = emitter;
            this.station = station;
        }

        void stop() {
            running = false;
            open.remove(this);
        }

        void run() {
            // Cursors are in database time (the clock that stamps updated_at), never JVM time.
            Instant cursor = board.now();
            Instant started = Instant.now();
            Instant lastBeat = started;
            try {
                List<KitchenTicketView> snapshot = board.tickets(new Limit(Limit.MAX), true, station);
                for (KitchenTicketView t : snapshot.reversed()) {
                    send(t);
                }
                while (running) {
                    Thread.sleep(pollInterval);
                    List<KitchenTicketView> changes = board.changedSince(cursor.minus(OVERLAP), station);
                    for (KitchenTicketView t : changes) {
                        if (!t.updatedAt().equals(sent.get(t.id()))) {
                            send(t);
                        }
                    }
                    Instant floor = board.now().minus(OVERLAP);
                    cursor = changes.stream().map(KitchenTicketView::updatedAt).max(Comparator.naturalOrder())
                            .filter(c -> c.isAfter(floor))
                            .orElse(floor);
                    Instant horizon = cursor.minus(OVERLAP.multipliedBy(2));
                    sent.values().removeIf(at -> at.isBefore(horizon));
                    Instant now = Instant.now();
                    if (Duration.between(lastBeat, now).compareTo(HEARTBEAT) >= 0) {
                        emitter.send(SseEmitter.event().comment("ping"));
                        lastBeat = now;
                    }
                    if (Duration.between(started, now).compareTo(maxStreamAge) >= 0) {
                        break;
                    }
                }
                emitter.complete();
            } catch (IOException | IllegalStateException e) {
                // Client disconnected or emitter already completed.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("KDS stream failed: {}", e.toString());
                emitter.completeWithError(e);
            } finally {
                stop();
            }
        }

        private void send(KitchenTicketView ticket) throws IOException {
            emitter.send(SseEmitter.event().name("ticket").id(ticket.id() + "@" + ticket.updatedAt())
                    .data(ticket, MediaType.APPLICATION_JSON));
            sent.put(ticket.id(), ticket.updatedAt());
        }
    }
}
