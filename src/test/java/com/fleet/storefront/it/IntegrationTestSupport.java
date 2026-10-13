package com.fleet.storefront.it;

import com.fleet.storefront.TestcontainersConfiguration;
import com.fleet.storefront.application.port.in.PlaceOrderCommand;
import com.fleet.storefront.application.port.in.PlaceOrderUseCase;
import com.fleet.storefront.domain.catalog.Sku;
import com.fleet.storefront.domain.order.Quantity;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.shared.UuidV7;
import com.fleet.storefront.domain.terminal.ApiKeys;
import com.fleet.storefront.domain.terminal.Role;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Base for integration tests: real PostgreSQL (Testcontainers), Flyway-migrated schema, and the outbox
 * scheduler disabled so tests drive the relay deterministically.
 */
@SpringBootTest(properties = {"storefront.outbox.relay-enabled=false", "storefront.housekeeping.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestSupport {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected PlaceOrderUseCase placeOrder;

    /** Device keys seeded before every test (see {@link #as(String)}). */
    protected static final String MANAGER_KEY = "sf_it_manager_key";
    protected static final String POS_KEY = "sf_it_pos_key";
    protected static final String KITCHEN_KEY = "sf_it_kitchen_key";
    protected static final String HQ_KEY = "sf_it_hq_key";

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("""
                TRUNCATE kitchen_ticket, outbox_event, idempotency_key, refund_line, refund, payment, order_line,
                         customer_order, fiscal_sequence, stock_movement, inventory, product, terminal, sync_state
                """).update();
        givenDevice("it-manager", Role.MANAGER, MANAGER_KEY);
        givenDevice("kiosk-1", Role.POS, POS_KEY);
        givenDevice("kds-1", Role.KITCHEN, KITCHEN_KEY);
        givenDevice("hq", Role.HQ, HQ_KEY);
    }

    protected void givenDevice(String terminalId, Role role, String apiKey) {
        jdbc.sql("""
                        INSERT INTO terminal (id, terminal_id, name, role, api_key_hash, created_at)
                        VALUES (:id, :terminal, :name, :role, :hash, now())
                        """)
                .param("id", UuidV7.next()).param("terminal", terminalId).param("name", terminalId + " device")
                .param("role", role.name()).param("hash", ApiKeys.hash(apiKey))
                .update();
    }

    /** Authenticates a MockMvc request with an API key ({@link #MANAGER_KEY}, {@link #POS_KEY}, …). */
    protected static RequestPostProcessor as(String apiKey) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + apiKey);
            return request;
        };
    }

    protected UUID givenProduct(String sku, String price, boolean requiresPreparation, int onHand) {
        UUID id = UuidV7.next();
        jdbc.sql("""
                        INSERT INTO product (id, sku, name, unit_price, currency, tax_rate_percent, requires_preparation)
                        VALUES (:id, :sku, :name, :price, 'USD', 8.25, :prep)
                        """)
                .param("id", id).param("sku", sku).param("name", sku + " item")
                .param("price", new BigDecimal(price)).param("prep", requiresPreparation)
                .update();
        jdbc.sql("INSERT INTO inventory (product_id, on_hand) VALUES (:id, :qty)")
                .param("id", id).param("qty", onHand).update();
        return id;
    }

    protected int stockOf(String sku) {
        return jdbc.sql("SELECT i.on_hand FROM inventory i JOIN product p ON p.id = i.product_id WHERE p.sku = :sku")
                .param("sku", sku).query(Integer.class).single();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    protected static PlaceOrderCommand order(String terminal, String key, Object... skuQtyPairs) {
        List<PlaceOrderCommand.Item> items = new ArrayList<>();
        for (int i = 0; i < skuQtyPairs.length; i += 2) {
            items.add(new PlaceOrderCommand.Item(Sku.of((String) skuQtyPairs[i]), Quantity.of((Integer) skuQtyPairs[i + 1])));
        }
        return new PlaceOrderCommand(new TerminalId(terminal), key, items);
    }

    /** Outcome of one concurrent call: either a value or the exception it threw. */
    protected record Outcome<T>(T value, Throwable error) {
        boolean ok() {
            return error == null;
        }
    }

    /**
     * Starts every task on its own thread and releases them simultaneously through a start gate, so
     * they genuinely race for the same rows.
     */
    protected static <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(new Outcome<>(future.get(60, TimeUnit.SECONDS), null));
                } catch (java.util.concurrent.ExecutionException e) {
                    outcomes.add(new Outcome<>(null, e.getCause()));
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new AssertionError("Task hung — possible deadlock or starvation", e);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
