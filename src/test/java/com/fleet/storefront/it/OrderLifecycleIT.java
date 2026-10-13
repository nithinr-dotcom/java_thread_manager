package com.fleet.storefront.it;

import com.fleet.storefront.adapter.out.outbox.OutboxRelay;
import com.fleet.storefront.application.port.in.CancelOrderUseCase;
import com.fleet.storefront.application.port.in.PayOrderUseCase;
import com.fleet.storefront.application.port.in.PlaceOrderResult;
import com.fleet.storefront.application.port.in.RefundOrderUseCase;
import com.fleet.storefront.domain.order.OrderId;
import com.fleet.storefront.domain.order.TerminalId;
import com.fleet.storefront.domain.payment.PaymentMethod;
import com.fleet.storefront.domain.shared.DomainException;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payments, cancellation and refunds over HTTP and under contention. */
class OrderLifecycleIT extends IntegrationTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    PayOrderUseCase pay;

    @Autowired
    CancelOrderUseCase cancel;

    @Autowired
    RefundOrderUseCase refund;

    @Autowired
    OutboxRelay relay;

    @Test
    void cashPaymentOverHttp_computesChange_settlesTheOrder_andReplaysSafely() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        String id = id(placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 2)));   // total 24.68

        String payment = "{\"method\":\"CASH\",\"amount\":24.68,\"tendered\":30}";
        mvc.perform(post("/api/v1/orders/{id}/payments", id).with(as(POS_KEY)).header("Idempotency-Key", "p1")
                        .contentType(MediaType.APPLICATION_JSON).content(payment))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.payment.change").value(5.32))
                .andExpect(jsonPath("$.order.status").value("PAID"))
                .andExpect(jsonPath("$.order.amountDue").value(0))
                .andExpect(jsonPath("$.order.amountPaid").value(24.68));
        mvc.perform(post("/api/v1/orders/{id}/payments", id).with(as(POS_KEY)).header("Idempotency-Key", "p1")
                        .contentType(MediaType.APPLICATION_JSON).content(payment))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.order.payments.length()").value(1));
        mvc.perform(post("/api/v1/orders/{id}/payments", id).with(as(POS_KEY)).header("Idempotency-Key", "p2")
                        .contentType(MediaType.APPLICATION_JSON).content(payment))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
        assertThat(count("payment")).isEqualTo(1);
    }

    @Test
    void paymentValidation() throws Exception {
        givenProduct("WATER", "2.50", false, 10);
        String id = id(placeOrder.placeOrder(order("kiosk-1", "k1", "WATER", 1)));   // 2.71

        payExpect(id, "a", "{\"method\":\"CASH\",\"amount\":2.71,\"tendered\":2.00}", 422, "INSUFFICIENT_TENDER");
        payExpect(id, "b", "{\"method\":\"CARD\",\"amount\":3.00,\"reference\":\"A1\"}", 422, "PAYMENT_EXCEEDS_DUE");
        payExpect(id, "c", "{\"method\":\"CARD\",\"amount\":2.711,\"reference\":\"A1\"}", 400, "INVALID_REQUEST");
        payExpect(id, "d", "{\"method\":\"CARD\",\"amount\":2.71}", 400, "INVALID_REQUEST");
        payExpect(id, "e", "{\"method\":\"BITCOIN\",\"amount\":2.71}", 400, "INVALID_REQUEST");
        payExpect("0192d0a0-0000-7000-8000-000000000000", "f", "{\"method\":\"CARD\",\"amount\":1,\"reference\":\"A\"}",
                404, "ORDER_NOT_FOUND");
    }

    @Test
    void concurrentTendersNeverOverpay_exactlyOneFullPaymentWins() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        PlaceOrderResult placed = placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 2));
        OrderId orderId = placed.order().id();

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String key = "pay-" + i;
            tasks.add(() -> pay.pay(new PayOrderUseCase.PayOrderCommand(new TerminalId("kiosk-1"), key, orderId,
                    PaymentMethod.CARD, new BigDecimal("24.68"), null, "AUTH-" + key)));
        }
        List<Outcome<Object>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(Outcome::ok).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.ok())
                .allMatch(o -> o.error() instanceof DomainException d && d.code().equals("ORDER_NOT_PAYABLE"));
        assertThat(jdbc.sql("SELECT sum(amount) FROM payment").query(BigDecimal.class).single())
                .isEqualByComparingTo("24.68");
    }

    @Test
    void cancellingAnUnpaidOrderReturnsStock_journalsIt_andCancelsTheKitchenTicket() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        String id = id(placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 3)));
        relay.drainOnce();   // ticket reaches the kitchen
        assertThat(stockOf("BURGER")).isEqualTo(7);

        mvc.perform(post("/api/v1/orders/{id}/cancel", id).with(as(POS_KEY)).header("Idempotency-Key", "c1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Customer left\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        relay.drainOnce();

        assertThat(stockOf("BURGER")).isEqualTo(10);
        assertThat(jdbc.sql("SELECT reason FROM stock_movement ORDER BY created_at, id").query(String.class).list())
                .containsExactly("SALE", "CANCEL");
        assertThat(jdbc.sql("SELECT status FROM kitchen_ticket").query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT cancel_reason FROM customer_order").query(String.class).single())
                .isEqualTo("Customer left");
        assertThat(jdbc.sql("SELECT max(last_value) FROM fiscal_sequence").query(Long.class).single())
                .as("the cancelled document keeps its number").isEqualTo(1);
    }

    @Test
    void cancellationDeliveredBeforeTheTicketRequest_neverResurrectsTheTicket() {
        givenProduct("BURGER", "11.40", true, 10);
        OrderId id = placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 1)).order().id();
        cancel.cancel(new CancelOrderUseCase.CancelOrderCommand(new TerminalId("kiosk-1"), "c1", id, "Mistake"));
        // Deliver the cancellation first, then the original request (e.g. the request had been backing off).
        jdbc.sql("UPDATE outbox_event SET created_at = created_at - INTERVAL '1 hour' WHERE event_type = 'KitchenTicketCancelled'")
                .update();
        relay.drainOnce();
        relay.drainOnce();

        assertThat(jdbc.sql("SELECT status FROM kitchen_ticket").query(String.class).single()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelRacingPayment_exactlyOneWins() throws Exception {
        givenProduct("WATER", "2.50", false, 100);
        for (int round = 0; round < 10; round++) {
            OrderId id = placeOrder.placeOrder(order("kiosk-1", "o-" + round, "WATER", 1)).order().id();
            int r = round;
            List<Outcome<Object>> outcomes = runConcurrently(List.<Callable<Object>>of(
                    () -> pay.pay(new PayOrderUseCase.PayOrderCommand(new TerminalId("kiosk-1"), "p-" + r, id,
                            PaymentMethod.CARD, new BigDecimal("2.71"), null, "AUTH")),
                    () -> cancel.cancel(new CancelOrderUseCase.CancelOrderCommand(new TerminalId("kiosk-1"), "c-" + r,
                            id, "race"))));
            assertThat(outcomes).filteredOn(Outcome::ok).hasSize(1);
            String status = jdbc.sql("SELECT status FROM customer_order WHERE id = :id").param("id", id.value())
                    .query(String.class).single();
            assertThat(status).isIn("PAID", "CANCELLED");
        }
        assertThat(stockOf("WATER")).isEqualTo(100 - (int) (long) jdbc
                .sql("SELECT count(*) FROM customer_order WHERE status = 'PAID'").query(Long.class).single());
    }

    @Test
    void refundsOverHttp_areNumberedDocuments_andAFullRefundReturnsExactlyTheTotal() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        String id = id(placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 2)));
        mvc.perform(post("/api/v1/orders/{id}/payments", id).with(as(POS_KEY)).header("Idempotency-Key", "p1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD\",\"amount\":24.68,\"reference\":\"A\"}"));

        String body = "{\"lines\":[{\"lineNo\":1,\"quantity\":1}],\"restock\":true,\"method\":\"CARD\",\"reason\":\"Cold\"}";
        mvc.perform(post("/api/v1/orders/{id}/refunds", id).with(as(POS_KEY)).header("Idempotency-Key", "r1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/{id}/refunds", id).with(as(MANAGER_KEY)).header("Idempotency-Key", "r1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refund.refundNumber").value(matchesPattern("REF-HOSP01-\\d{8}-0001")))
                .andExpect(jsonPath("$.refund.total").value(12.34))
                .andExpect(jsonPath("$.order.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.order.lines[0].refundedQuantity").value(1));
        mvc.perform(post("/api/v1/orders/{id}/refunds", id).with(as(MANAGER_KEY)).header("Idempotency-Key", "r2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refund.total").value(12.34))
                .andExpect(jsonPath("$.order.status").value("REFUNDED"))
                .andExpect(jsonPath("$.order.amountRefunded").value(24.68));
        mvc.perform(post("/api/v1/orders/{id}/refunds", id).with(as(MANAGER_KEY)).header("Idempotency-Key", "r3")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_REFUNDABLE"));
        mvc.perform(get("/api/v1/orders/{id}", id).with(as(MANAGER_KEY)))
                .andExpect(jsonPath("$.refunds.length()").value(2));
        assertThat(stockOf("BURGER")).isEqualTo(10);
    }

    @Test
    void concurrentRefundsOfOneLine_neverExceedTheSoldUnits_andRefundNumbersStayGapFree() throws Exception {
        givenProduct("MEALBOX", "8.95", false, 50);
        OrderId id = placeOrder.placeOrder(order("kiosk-1", "k1", "MEALBOX", 5)).order().id();
        pay.pay(new PayOrderUseCase.PayOrderCommand(new TerminalId("kiosk-1"), "p1", id, PaymentMethod.CARD,
                new BigDecimal("48.44"), null, "AUTH"));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String key = "r-" + i;
            tasks.add(() -> {
                TreeMap<Integer, Integer> one = new TreeMap<>();
                one.put(1, 1);
                return refund.refund(new RefundOrderUseCase.RefundOrderCommand(new TerminalId("it-manager"), key, id,
                        one, true, PaymentMethod.CARD, "race"));
            });
        }
        List<Outcome<Object>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).filteredOn(Outcome::ok).hasSize(5);
        assertThat(outcomes).filteredOn(o -> !o.ok()).allMatch(o -> o.error() instanceof DomainException d
                && (d.code().equals("REFUND_EXCEEDS_SOLD") || d.code().equals("ORDER_NOT_REFUNDABLE")));
        assertThat(jdbc.sql("SELECT sequence_no FROM refund ORDER BY sequence_no").query(Long.class).list())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(jdbc.sql("SELECT sum(grand_total) FROM refund").query(BigDecimal.class).single())
                .isEqualByComparingTo("48.44");
        assertThat(stockOf("MEALBOX")).isEqualTo(50);
    }

    @Test
    void voidingAnAbandonedSplitTenderOverHttp_letsTheOrderBeCancelled() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        String id = id(placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 2)));   // 24.68
        String paymentId = JsonPath.read(mvc.perform(post("/api/v1/orders/{id}/payments", id)
                        .with(as(POS_KEY)).header("Idempotency-Key", "p1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CASH\",\"amount\":10,\"tendered\":10}"))
                .andReturn().getResponse().getContentAsString(), "$.payment.id");

        mvc.perform(post("/api/v1/orders/{id}/payments/{pid}/void", id, paymentId).with(as(POS_KEY))
                        .header("Idempotency-Key", "v1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Card declined, cash returned\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountPaid").value(0))
                .andExpect(jsonPath("$.payments[0].voidReason").value("Card declined, cash returned"));
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).with(as(POS_KEY)).header("Idempotency-Key", "c1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Customer left\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stockOf("BURGER")).isEqualTo(10);
    }

    /** Lifecycle locks (key → order → inventory → REF counter) under contention with checkouts on the same rows. */
    @Test
    void cancelsAndRestockingRefundsRacingCheckoutsOnTheSameProducts_neverDeadlockOrLoseUnits() throws Exception {
        givenProduct("ALPHA", "1.00", false, 1000);
        givenProduct("BETA", "1.00", false, 1000);
        List<OrderId> toCancel = new ArrayList<>();
        List<OrderId> toRefund = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            toCancel.add(placeOrder.placeOrder(order("kiosk-1", "c-" + i, "ALPHA", 2, "BETA", 3)).order().id());
            OrderId paid = placeOrder.placeOrder(order("kiosk-1", "r-" + i, "BETA", 3, "ALPHA", 2)).order().id();
            pay.pay(new PayOrderUseCase.PayOrderCommand(new TerminalId("kiosk-1"), "p-" + i, paid, PaymentMethod.CARD,
                    new BigDecimal("5.41"), null, "AUTH"));
            toRefund.add(paid);
        }
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int n = i;
            tasks.add(() -> cancel.cancel(new CancelOrderUseCase.CancelOrderCommand(new TerminalId("kiosk-1"), "cx-" + n,
                    toCancel.get(n), "race")));
            tasks.add(() -> {
                TreeMap<Integer, Integer> all = new TreeMap<>();
                all.put(1, 2);
                all.put(2, 3);
                return refund.refund(new RefundOrderUseCase.RefundOrderCommand(new TerminalId("it-manager"), "rx-" + n,
                        toRefund.get(n), all, true, PaymentMethod.CARD, "race"));
            });
            tasks.add(() -> placeOrder.placeOrder(order("kiosk-1", "a-" + n, "ALPHA", 1, "BETA", 1)));
            tasks.add(() -> placeOrder.placeOrder(order("kiosk-1", "b-" + n, "BETA", 1, "ALPHA", 1)));
        }
        List<Outcome<Object>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::ok);
        // 20 lifecycle orders were fully returned; only the 20 racing checkouts keep stock (1 unit each).
        assertThat(stockOf("ALPHA")).isEqualTo(1000 - 20);
        assertThat(stockOf("BETA")).isEqualTo(1000 - 20);
        assertThat(jdbc.sql("SELECT sequence_no FROM refund ORDER BY sequence_no").query(Long.class).list())
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 10).boxed().toList());
        assertThat(jdbc.sql("SELECT sum(delta) FROM stock_movement WHERE product_id = (SELECT id FROM product WHERE sku = 'ALPHA')")
                .query(Long.class).single()).isEqualTo(stockOf("ALPHA") - 1000L);
    }

    private void payExpect(String id, String key, String body, int status, String code) throws Exception {
        mvc.perform(post("/api/v1/orders/{id}/payments", id).with(as(POS_KEY)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static String id(PlaceOrderResult result) {
        return result.order().id().toString();
    }
}
