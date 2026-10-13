package com.fleet.storefront.it;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression: read endpoints called outside a transaction (the UI polls them) must return every pooled
 * connection. A lazy JdbcClient stream once leaked one connection per call until the pool was exhausted.
 */
class ConnectionLeakIT extends IntegrationTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    DataSource dataSource;

    @Test
    void pollingReadEndpointsNeverLeaksConnections() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        String id = placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 1)).order().id().toString();
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        int max = pool.getMaximumPoolSize();

        for (int i = 0; i < max * 3; i++) {
            mvc.perform(get("/api/v1/catalog").with(as(POS_KEY))).andExpect(status().isOk());
            mvc.perform(get("/api/v1/orders").with(as(POS_KEY))).andExpect(status().isOk());
            mvc.perform(get("/api/v1/orders/{id}", id).with(as(POS_KEY))).andExpect(status().isOk());
            mvc.perform(get("/api/v1/kitchen/tickets").with(as(KITCHEN_KEY))).andExpect(status().isOk());
        }

        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
