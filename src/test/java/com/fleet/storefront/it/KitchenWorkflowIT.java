package com.fleet.storefront.it;

import com.fleet.storefront.adapter.out.outbox.OutboxRelay;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** KDS workflow over HTTP: transitions, conflicts, station routing. */
class KitchenWorkflowIT extends IntegrationTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxRelay relay;

    @Test
    void ticketsMoveThroughTheWorkflow_andIllegalOrStaleMovesConflict() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 1));
        relay.drainOnce();
        String id = JsonPath.read(mvc.perform(get("/api/v1/kitchen/tickets").with(as(KITCHEN_KEY)))
                .andReturn().getResponse().getContentAsString(), "$[0].id");

        move(id, "IN_PROGRESS").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        move(id, "IN_PROGRESS").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_STATE_CONFLICT"));
        move(id, "READY").andExpect(status().isOk());
        move(id, "SERVED").andExpect(status().isOk());
        move(id, "QUEUED").andExpect(status().isConflict());
        move("0192d0a0-0000-7000-8000-000000000000", "READY").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));

        mvc.perform(get("/api/v1/kitchen/tickets").param("active", "true").with(as(KITCHEN_KEY)))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/kitchen/tickets").with(as(KITCHEN_KEY)))
                .andExpect(jsonPath("$[0].status").value("SERVED"));
    }

    @Test
    void stationFilterShowsOnlyTicketsWithItemsForThatStation() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        givenProduct("LATTE", "4.75", true, 10);
        jdbc.sql("UPDATE product SET station = 'GRILL' WHERE sku = 'BURGER'").update();
        jdbc.sql("UPDATE product SET station = 'BAR' WHERE sku = 'LATTE'").update();
        placeOrder.placeOrder(order("kiosk-1", "k1", "BURGER", 1));
        placeOrder.placeOrder(order("kiosk-1", "k2", "LATTE", 1));
        placeOrder.placeOrder(order("kiosk-1", "k3", "BURGER", 1, "LATTE", 1));
        relay.drainOnce();

        List<String> grill = JsonPath.read(mvc.perform(get("/api/v1/kitchen/tickets").param("station", "grill")
                .with(as(KITCHEN_KEY))).andReturn().getResponse().getContentAsString(), "$[*].items[*].station");
        assertThat(grill).contains("GRILL");
        mvc.perform(get("/api/v1/kitchen/tickets").param("station", "GRILL").with(as(KITCHEN_KEY)))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/kitchen/tickets").param("station", "BAR").with(as(KITCHEN_KEY)))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/kitchen/tickets").param("station", "PASTRY").with(as(KITCHEN_KEY)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private org.springframework.test.web.servlet.ResultActions move(String id, String status) throws Exception {
        return mvc.perform(post("/api/v1/kitchen/tickets/{id}/status", id).with(as(KITCHEN_KEY))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }
}
