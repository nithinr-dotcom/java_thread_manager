package com.fleet.storefront.it;

import com.fleet.storefront.application.port.in.HousekeepingUseCase;
import com.fleet.storefront.application.port.in.PayOrderUseCase;
import com.fleet.storefront.application.port.in.PlaceOrderResult;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.payment.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportsAndHousekeepingIT extends IntegrationTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    PayOrderUseCase pay;

    @Autowired
    HousekeepingUseCase housekeeping;

    @Test
    void dailyReportAndFiscalChainReflectTheDay_andTamperingIsDetected() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        givenProduct("WATER", "2.50", false, 10);
        PlaceOrderResult paid = placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 2));   // 24.68
        pay.pay(new PayOrderUseCase.PayOrderCommand(new TerminalId("kiosk-1"), "p1", paid.order().id(),
                PaymentMethod.CASH, new BigDecimal("24.68"), new BigDecimal("30"), null));
        placeOrder.placeOrder(order("kiosk-1", "k2", "WATER", 1));                            // 2.71 unpaid
        String date = paid.order().number().businessDate().toString();

        mvc.perform(get("/api/v1/reports/daily").param("date", date).with(as(MANAGER_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.issued").value(2))
                .andExpect(jsonPath("$.orders.firstNumber").value(paid.order().number().value()))
                .andExpect(jsonPath("$.sales.total").value(27.39))
                .andExpect(jsonPath("$.taxByRate[0].taxRatePercent").value(8.25))
                .andExpect(jsonPath("$.taxByRate[0].net").value(25.30))
                .andExpect(jsonPath("$.payments[0].method").value("CASH"))
                .andExpect(jsonPath("$.payments[0].amount").value(24.68))
                .andExpect(jsonPath("$.unpaidTotal").value(2.71));
        mvc.perform(get("/api/v1/reports/fiscal-chain").param("date", date).with(as(MANAGER_KEY)))
                .andExpect(jsonPath("$.series[0].series").value("ORD"))
                .andExpect(jsonPath("$.series[0].documents").value(2))
                .andExpect(jsonPath("$.series[0].valid").value(true));

        jdbc.sql("DELETE FROM order_line WHERE sku = 'WATER'").update();
        jdbc.sql("DELETE FROM idempotency_key").update();
        jdbc.sql("DELETE FROM customer_order WHERE order_number LIKE '%-0002'").update();
        mvc.perform(get("/api/v1/reports/fiscal-chain").param("date", date).with(as(MANAGER_KEY)))
                .andExpect(jsonPath("$.series[0].valid").value(false))
                .andExpect(jsonPath("$.series[0].firstBrokenNumber").value(endsWith("-0002")));

        jdbc.sql("UPDATE order_line SET quantity = 1 WHERE sku = 'BURGER'").update();
        mvc.perform(get("/api/v1/reports/fiscal-chain").param("date", date).with(as(MANAGER_KEY)))
                .andExpect(jsonPath("$.series[0].valid").value(false))
                .andExpect(jsonPath("$.series[0].firstBrokenNumber").value(paid.order().number().value()));
    }

    @Test
    void documentTimeNeverRunsBackwards_evenIfTheWallClockDoes() {
        givenProduct("WATER", "2.50", false, 10);
        PlaceOrderResult first = placeOrder.placeOrder(order("kiosk-1", "k1", "WATER", 1));
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        // Simulates a previous document stamped by a clock that was ahead (then stepped back by NTP).
        jdbc.sql("UPDATE fiscal_sequence SET last_issued_at = :t").param("t", Timestamp.from(future)).update();

        PlaceOrderResult second = placeOrder.placeOrder(order("kiosk-1", "k2", "WATER", 1));

        assertThat(second.order().placedAt()).isEqualTo(future).isAfter(first.order().placedAt());
    }

    @Test
    void housekeepingPurgesOnlyExpiredKeysAndDeliveredEvents() {
        givenProduct("WATER", "2.50", false, 10);
        placeOrder.placeOrder(order("kiosk-1", "old", "WATER", 1));
        placeOrder.placeOrder(order("kiosk-1", "new", "WATER", 1));
        jdbc.sql("UPDATE idempotency_key SET created_at = now() - INTERVAL '8 days' WHERE idempotency_key = 'old'").update();
        jdbc.sql("UPDATE outbox_event SET published_at = now() - INTERVAL '8 days' "
                + "WHERE id = (SELECT id FROM outbox_event ORDER BY created_at LIMIT 1)").update();

        HousekeepingUseCase.PurgeResult result = housekeeping.purgeExpired();

        assertThat(result).isEqualTo(new HousekeepingUseCase.PurgeResult(1, 1));
        assertThat(jdbc.sql("SELECT idempotency_key FROM idempotency_key").query(String.class).list()).containsExactly("new");
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(placeOrder.placeOrder(order("kiosk-1", "new", "WATER", 1)).replayed())
                .as("a key inside retention still replays").isTrue();
    }
}
