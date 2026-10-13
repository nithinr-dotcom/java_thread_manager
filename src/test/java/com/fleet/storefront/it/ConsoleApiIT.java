package com.fleet.storefront.it;

import com.fleet.storefront.adapter.out.outbox.OutboxRelay;
import com.fleet.storefront.domain.shared.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Read and operations endpoints used by the test console (docs/API.md). */
class ConsoleApiIT extends IntegrationTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxRelay relay;

    @Test
    void catalogListsProductsWithLiveStockInSkuOrder() throws Exception {
        givenProduct("WATER", "2.50", false, 7);
        givenProduct("BURGER", "11.40", true, 3);

        mvc.perform(get("/api/v1/catalog").with(as(POS_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sku").value("BURGER"))
                .andExpect(jsonPath("$[0].unitPrice").value(11.40))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].requiresPreparation").value(true))
                .andExpect(jsonPath("$[0].onHand").value(3))
                .andExpect(jsonPath("$[1].sku").value("WATER"));
    }

    @Test
    void restockIncrementsStockAndRejectsBadInput() throws Exception {
        givenProduct("WATER", "2.50", false, 0);

        restock("water", "{\"quantity\":5}").andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("WATER"))
                .andExpect(jsonPath("$.onHand").value(5));
        restock("WATER", "{\"quantity\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        restock("NOPE", "{\"quantity\":1}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PRODUCT"));
    }

    @Test
    void recentOrdersAreListedNewestFirst_andKitchenAndOutboxStatusReflectDelivery() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        placeOrder.placeOrder(order("pos-1", "k1", "BURGER", 1));
        placeOrder.placeOrder(order("pos-2", "k2", "BURGER", 2));

        mvc.perform(get("/api/v1/orders").with(as(MANAGER_KEY)).param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].terminalId").value("pos-2"))
                .andExpect(jsonPath("$[0].lines[0].quantity").value(2))
                .andExpect(jsonPath("$[1].terminalId").value("pos-1"));

        // Before the relay runs: both destinations have pending events, the kitchen has nothing yet.
        mvc.perform(get("/api/v1/ops/outbox").with(as(MANAGER_KEY)))
                .andExpect(jsonPath("$.destinations[0].destination").value("HQ"))
                .andExpect(jsonPath("$.destinations[0].pending").value(2))
                .andExpect(jsonPath("$.destinations[1].destination").value("KITCHEN"))
                .andExpect(jsonPath("$.destinations[1].pending").value(2))
                .andExpect(jsonPath("$.destinations[1].oldestPendingAt").isNotEmpty());
        mvc.perform(get("/api/v1/kitchen/tickets").with(as(KITCHEN_KEY))).andExpect(jsonPath("$.length()").value(0));

        relay.drainOnce();

        mvc.perform(get("/api/v1/kitchen/tickets").with(as(KITCHEN_KEY)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("QUEUED"))
                .andExpect(jsonPath("$[0].items[0].sku").value("BURGER"));
        mvc.perform(get("/api/v1/ops/outbox").with(as(MANAGER_KEY)))
                .andExpect(jsonPath("$.destinations[0].pending").value(2))   // HQ offline: stays queued
                .andExpect(jsonPath("$.destinations[1].pending").value(0))
                .andExpect(jsonPath("$.destinations[1].published").value(2))
                .andExpect(jsonPath("$.destinations[1].oldestPendingAt").value(nullValue()));
    }

    @Test
    void outOfRangeLimitsAndMissingHeadersAreInvalidRequests() throws Exception {
        mvc.perform(get("/api/v1/orders").with(as(MANAGER_KEY)).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/kitchen/tickets").with(as(KITCHEN_KEY)).param("limit", "201"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").with(as(POS_KEY)).header("X-Terminal-Id", "kiosk-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"sku\":\"A\",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private ResultActions restock(String sku, String body) throws Exception {
        return mvc.perform(post("/api/v1/inventory/{sku}/restock", sku).with(as(MANAGER_KEY))
                .header("Idempotency-Key", UuidV7.next().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
