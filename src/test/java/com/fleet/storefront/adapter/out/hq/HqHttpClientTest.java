package com.fleet.storefront.adapter.out.hq;

import com.fleet.storefront.adapter.out.outbox.NonRetryableDispatchException;
import com.fleet.storefront.adapter.out.outbox.OutboxRecord;
import com.fleet.storefront.domain.shared.UuidV7;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The HQ link is bounded by timeouts, authenticates, and classifies failures (no Spring context). */
class HqHttpClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aHungHqFailsWithinTheReadTimeout() throws IOException {
        start(500, 5_000);
        HqSyncDispatcher dispatcher = new HqSyncDispatcher(client(Duration.ofMillis(300)));

        long started = System.nanoTime();
        assertThatThrownBy(() -> dispatcher.dispatch(record())).isNotInstanceOf(NonRetryableDispatchException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void malformedRequestsAreNotRetried_butServerErrorsAre() throws IOException {
        start(400, 0);
        HqSyncDispatcher dispatcher = new HqSyncDispatcher(client(Duration.ofSeconds(2)));
        assertThatThrownBy(() -> dispatcher.dispatch(record())).isInstanceOf(NonRetryableDispatchException.class);
        server.stop(0);

        start(503, 0);
        HqSyncDispatcher retrying = new HqSyncDispatcher(client(Duration.ofSeconds(2)));
        assertThatThrownBy(() -> retrying.dispatch(record())).isNotInstanceOf(NonRetryableDispatchException.class);
    }

    @Test
    void sendsTheEventIdAsIdempotencyKey_theNodeIdentity_andTheHqCredential() throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Idempotency-Key") + "|"
                    + exchange.getRequestHeaders().getFirst("X-Origin-Node") + "|"
                    + exchange.getRequestHeaders().getFirst("Authorization") + "|" + exchange.getRequestURI());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        OutboxRecord record = record();

        new HqSyncDispatcher(client(Duration.ofSeconds(2))).dispatch(record);

        assertThat(seen.get()).isEqualTo(record.id() + "|node-1|Bearer hq-secret|/api/v1/sync/events/OrderPlaced");
    }

    private void start(int status, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    private HqHttpClient client(Duration readTimeout) {
        return new HqHttpClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), "hq-secret",
                "node-1", Duration.ofSeconds(1), readTimeout);
    }

    private static OutboxRecord record() {
        return new OutboxRecord(UuidV7.next(), "HQ", "OrderPlaced", UuidV7.next(), "{}", 1, Instant.now());
    }
}
