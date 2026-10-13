package com.fleet.storefront.it;

import com.fleet.storefront.adapter.out.kitchen.KitchenTicketInbox;
import com.fleet.storefront.adapter.out.outbox.OutboxRelay;
import com.fleet.storefront.adapter.out.outbox.OutboxStore;
import com.fleet.storefront.application.port.in.PlaceOrderResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** Scenario D: transactional outbox → kitchen, with failure isolation and crash recovery. */
class KitchenDispatchIT extends IntegrationTestSupport {

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxStore outboxStore;

    @MockitoSpyBean
    KitchenTicketInbox kitchen;

    @Test
    void placedOrderWithPreparedFood_reachesTheKitchen() {
        givenProduct("BURGER", "11.40", true, 10);
        givenProduct("WATER", "2.50", false, 10);
        PlaceOrderResult result = placeOrder.placeOrder(order("pos-1", "k1", "BURGER", 2, "WATER", 1));

        assertThat(kitchenEvent(result).get("published_at")).isNull();
        relay.drainOnce();

        Map<String, Object> ticket = jdbc.sql("SELECT order_number, items::text AS items, status FROM kitchen_ticket")
                .query().singleRow();
        assertThat(ticket.get("order_number")).isEqualTo(result.order().number().value());
        assertThat((String) ticket.get("items")).contains("BURGER").doesNotContain("WATER");
        assertThat(ticket.get("status")).isEqualTo("QUEUED");
        assertThat(kitchenEvent(result).get("published_at")).isNotNull();
    }

    @Test
    void kitchenOutage_neverAffectsCheckout_andTicketIsDeliveredOnceKitchenRecovers() {
        givenProduct("BURGER", "11.40", true, 10);
        doThrow(new DataAccessResourceFailureException("KDS unreachable")).when(kitchen).accept(any(), anyString());

        // Checkout completes normally even though the kitchen is down.
        PlaceOrderResult result = placeOrder.placeOrder(order("pos-1", "k1", "BURGER", 1));
        assertThat(result.order().number().sequence()).isEqualTo(1);

        relay.drainOnce();
        Map<String, Object> failed = kitchenEvent(result);
        assertThat(failed.get("published_at")).isNull();
        assertThat(failed.get("attempts")).isEqualTo(1);
        assertThat((String) failed.get("last_error")).contains("KDS unreachable");
        assertThat(count("kitchen_ticket")).isZero();

        // Kitchen comes back; once the backoff elapses the relay delivers.
        reset(kitchen);
        doCallRealMethod().when(kitchen).accept(any(), anyString());
        makeAllEventsDue();
        relay.drainOnce();

        assertThat(count("kitchen_ticket")).isEqualTo(1);
        assertThat(kitchenEvent(result).get("published_at")).isNotNull();
    }

    @Test
    void crashAfterCommit_beforeOrDuringDelivery_isRecoveredByLeaseExpiry_andKitchenGetsExactlyOneTicket() {
        givenProduct("BURGER", "11.40", true, 10);
        PlaceOrderResult result = placeOrder.placeOrder(order("pos-1", "k1", "BURGER", 1));

        // Simulate a relay that claimed the event, delivered it, and the node lost power before it could
        // mark the event as published.
        var claimed = outboxStore.claimDue("KITCHEN", 10, Duration.ofSeconds(30));
        assertThat(claimed).hasSize(1);
        relay.drainOnce();
        assertThat(count("kitchen_ticket")).as("lease still held: not redelivered yet").isZero();
        kitchen.accept(new com.fleet.storefront.domain.order.event.KitchenTicketRequested(
                result.order().id().value(), result.order().number().value(), "pos-1", result.order().placedAt(),
                java.util.List.of()), "[]");
        // --- crash here; after restart the lease has expired ---
        makeAllEventsDue();
        relay.drainOnce();

        assertThat(count("kitchen_ticket")).as("redelivery is absorbed by the idempotent consumer").isEqualTo(1);
        assertThat(kitchenEvent(result).get("published_at")).isNotNull();
        assertThat(kitchenEvent(result).get("attempts")).isEqualTo(2);
    }

    @Test
    void ordersWithoutPreparedFood_doNotCreateKitchenEvents_butAreQueuedForHq() {
        givenProduct("WATER", "2.50", false, 10);
        placeOrder.placeOrder(order("pos-1", "k1", "WATER", 1));

        assertThat(jdbc.sql("SELECT destination FROM outbox_event").query(String.class).list()).containsExactly("HQ");
        relay.drainOnce();
        // No HQ dispatcher configured (offline): the event waits, unpublished, for connectivity.
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL").query(Long.class).single())
                .isEqualTo(1);
    }

    private Map<String, Object> kitchenEvent(PlaceOrderResult result) {
        UUID orderId = result.order().id().value();
        return jdbc.sql("SELECT * FROM outbox_event WHERE destination = 'KITCHEN' AND aggregate_id = :id")
                .param("id", orderId).query().singleRow();
    }

    private void makeAllEventsDue() {
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = now() - INTERVAL '1 second' WHERE published_at IS NULL").update();
    }
}
