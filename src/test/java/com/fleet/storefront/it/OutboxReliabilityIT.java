package com.fleet.storefront.it;

import com.fleet.storefront.adapter.out.kitchen.KitchenTicketInbox;
import com.fleet.storefront.adapter.out.outbox.OutboxRelay;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dead letters: bounded retries, permanent failures, operator re-queue. */
@TestPropertySource(properties = "storefront.outbox.max-attempts=3")
class OutboxReliabilityIT extends IntegrationTestSupport {

    @Autowired
    OutboxRelay relay;

    @Autowired
    MockMvc mvc;

    @MockitoSpyBean
    KitchenTicketInbox kitchen;

    @Test
    void anEventThatKeepsFailingBecomesADeadLetter_andCanBeRequeuedByAnOperator() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 1));
        doThrow(new DataAccessResourceFailureException("KDS down")).when(kitchen).accept(any(), anyString());

        for (int i = 0; i < 3; i++) {
            makeAllEventsDue();
            relay.drainOnce();
        }

        assertThat(kitchenEvent("dead_at")).isNotNull();
        makeAllEventsDue();
        assertThat(relay.drainOnce()).as("dead letters are no longer retried").isZero();
        mvc.perform(get("/api/v1/ops/outbox").with(as(MANAGER_KEY)))
                .andExpect(jsonPath("$.destinations[1].destination").value("KITCHEN"))
                .andExpect(jsonPath("$.destinations[1].dead").value(1))
                .andExpect(jsonPath("$.destinations[1].pending").value(0));
        String id = mvc.perform(get("/api/v1/ops/outbox/dead").with(as(MANAGER_KEY)))
                .andExpect(jsonPath("$[0].eventType").value("KitchenTicketRequested"))
                .andExpect(jsonPath("$[0].attempts").value(3))
                .andReturn().getResponse().getContentAsString().replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");

        reset(kitchen);
        doCallRealMethod().when(kitchen).accept(any(), anyString());
        mvc.perform(post("/api/v1/ops/outbox/{id}/retry", id).with(as(MANAGER_KEY))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/ops/outbox/{id}/retry", id).with(as(MANAGER_KEY)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DEAD_LETTER_NOT_FOUND"));
        relay.drainOnce();

        assertThat(count("kitchen_ticket")).isEqualTo(1);
        assertThat(kitchenEvent("published_at")).isNotNull();
    }

    @Test
    void hqEventsAreRetriedForeverOnTransientFailures_andCanBeRequeuedInBulk() throws Exception {
        givenProduct("WATER", "2.50", false, 10);
        placeOrder.placeOrder(order("kiosk-1", "k1", "WATER", 1));
        placeOrder.placeOrder(order("kiosk-1", "k2", "WATER", 1));
        assertThat(relay.maxAttempts("HQ")).as("unlimited for HQ").isZero();
        assertThat(relay.maxAttempts("KITCHEN")).isEqualTo(3);

        jdbc.sql("UPDATE outbox_event SET dead_at = now(), attempts = 3, last_error = 'x' WHERE destination = 'HQ'").update();
        mvc.perform(post("/api/v1/ops/outbox/dead/retry").param("destination", "hq").with(as(MANAGER_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE dead_at IS NULL AND attempts = 0")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void anUnreadablePayloadIsDeadLetteredAtOnce_withoutBlockingOtherEvents() {
        givenProduct("BURGER", "11.40", true, 10);
        placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 1));
        placeOrder.placeOrder(order("kiosk-1", "k2", "BURGER", 1));
        jdbc.sql("""
                UPDATE outbox_event SET payload = '{"unexpected": true}'
                 WHERE id = (SELECT id FROM outbox_event WHERE destination = 'KITCHEN' ORDER BY created_at LIMIT 1)
                """).update();

        relay.drainOnce();

        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE dead_at IS NOT NULL").query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT attempts FROM outbox_event WHERE dead_at IS NOT NULL").query(Integer.class).single())
                .as("no pointless retries").isEqualTo(1);
        assertThat(count("kitchen_ticket")).isEqualTo(1);
    }

    private Object kitchenEvent(String column) {
        return jdbc.sql("SELECT " + column + " FROM outbox_event WHERE destination = 'KITCHEN'").query().singleValue();
    }

    private void makeAllEventsDue() {
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = now() - INTERVAL '1 second' WHERE published_at IS NULL").update();
    }
}
