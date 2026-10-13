package com.fleet.storefront.it;

import com.fleet.storefront.TestcontainersConfiguration;
import com.fleet.storefront.adapter.out.outbox.OutboxDispatcher;
import com.fleet.storefront.adapter.out.outbox.OutboxRecord;
import com.fleet.storefront.application.port.in.PlaceOrderUseCase;
import com.fleet.storefront.domain.shared.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the real relay threads running: a hung HQ link must not delay kitchen tickets (one worker per
 * destination). The HQ dispatcher here blocks until the test ends.
 */
@SpringBootTest(properties = {"storefront.outbox.relay-enabled=true", "storefront.housekeeping.enabled=false"})
@Import({TestcontainersConfiguration.class, RelayIsolationIT.HungHq.class})
@DirtiesContext // its live relay threads must not outlive this class and consume other tests' events
class RelayIsolationIT {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlaceOrderUseCase placeOrder;

    @Autowired
    CountDownLatch hqRelease;

    @Test
    void aHungHqLinkNeverDelaysKitchenTickets() throws Exception {
        jdbc.sql("TRUNCATE kitchen_ticket, outbox_event, idempotency_key, order_line, customer_order, stock_movement, inventory, product CASCADE").update();
        var id = UuidV7.next();
        jdbc.sql("""
                        INSERT INTO product (id, sku, name, unit_price, currency, tax_rate_percent, requires_preparation)
                        VALUES (:id, 'BURGER', 'Burger', :price, 'USD', 8.25, TRUE)
                        """).param("id", id).param("price", new BigDecimal("11.40")).update();
        jdbc.sql("INSERT INTO inventory (product_id, on_hand) VALUES (:id, 100)").param("id", id).update();
        try {
            for (int i = 0; i < 5; i++) {
                placeOrder.placeOrder(IntegrationTestSupport.order("pos-1", "k" + i, "BURGER", 1));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && count() < 5) {
                Thread.sleep(50);
            }
            assertThat(count()).as("all kitchen tickets delivered while HQ hangs").isEqualTo(5);
        } finally {
            hqRelease.countDown();
        }
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM kitchen_ticket").query(Long.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HungHq {

        @Bean
        CountDownLatch hqRelease() {
            return new CountDownLatch(1);
        }

        @Bean
        OutboxDispatcher hungHqDispatcher(CountDownLatch hqRelease) {
            return new OutboxDispatcher() {
                @Override
                public String destination() {
                    return "HQ";
                }

                @Override
                public void dispatch(OutboxRecord record) throws Exception {
                    hqRelease.await(30, TimeUnit.SECONDS);
                    throw new IllegalStateException("HQ unreachable");
                }
            };
        }
    }
}
