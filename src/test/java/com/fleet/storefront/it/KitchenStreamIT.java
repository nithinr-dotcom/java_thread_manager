package com.fleet.storefront.it;

import com.fleet.storefront.TestcontainersConfiguration;
import com.fleet.storefront.adapter.out.outbox.OutboxRelay;
import com.fleet.storefront.application.port.in.PlaceOrderUseCase;
import com.fleet.storefront.domain.shared.UuidV7;
import com.fleet.storefront.domain.terminal.ApiKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The live KDS stream over a real HTTP connection: snapshot first, then changes as they happen. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "storefront.outbox.relay-enabled=false", "storefront.housekeeping.enabled=false",
        "storefront.kitchen.stream-poll-interval=200ms"})
@Import(TestcontainersConfiguration.class)
@DirtiesContext
class KitchenStreamIT {

    private static final String KEY = "sf_stream_kitchen_key";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlaceOrderUseCase placeOrder;

    @Autowired
    OutboxRelay relay;

    @Test
    void streamSendsTheActiveSnapshot_thenNewTicketsAndStatusChanges() throws Exception {
        jdbc.sql("TRUNCATE kitchen_ticket, outbox_event, idempotency_key, order_line, customer_order, stock_movement, "
                + "inventory, product, terminal CASCADE").update();
        jdbc.sql("INSERT INTO terminal (id, terminal_id, name, role, api_key_hash, created_at) "
                        + "VALUES (:id, 'kds-s', 'KDS', 'KITCHEN', :hash, now())")
                .param("id", UuidV7.next()).param("hash", ApiKeys.hash(KEY)).update();
        var productId = UuidV7.next();
        jdbc.sql("""
                        INSERT INTO product (id, sku, name, unit_price, currency, tax_rate_percent, requires_preparation)
                        VALUES (:id, 'BURGER', 'Burger', :price, 'USD', 8.25, TRUE)
                        """).param("id", productId).param("price", new BigDecimal("11.40")).update();
        jdbc.sql("INSERT INTO inventory (product_id, on_hand) VALUES (:id, 100)").param("id", productId).update();
        placeOrder.placeOrder(IntegrationTestSupport.order("pos-1", "k1", "BURGER", 1));
        relay.drainOnce();

        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/kitchen/stream"))
                .header("Authorization", "Bearer " + KEY).build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        events.add(line.substring(5));
                    }
                }
            } catch (Exception ignored) {
                // stream closed by the test
            }
        });
        try {
            assertThat(events.poll(5, TimeUnit.SECONDS)).as("snapshot").contains("\"status\":\"QUEUED\"");

            placeOrder.placeOrder(IntegrationTestSupport.order("pos-1", "k2", "BURGER", 2));
            relay.drainOnce();
            assertThat(events.poll(5, TimeUnit.SECONDS)).as("new ticket").contains("\"quantity\":2");

            jdbc.sql("UPDATE kitchen_ticket SET status = 'READY', updated_at = clock_timestamp() WHERE items @> '[{\"quantity\":2}]'")
                    .update();
            assertThat(events.poll(5, TimeUnit.SECONDS)).as("status change").contains("\"status\":\"READY\"");
            assertThat(events.poll(Duration.ofMillis(800).toMillis(), TimeUnit.MILLISECONDS)).as("no duplicates").isNull();
        } finally {
            reader.interrupt();
            response.body().close();
        }
    }
}
