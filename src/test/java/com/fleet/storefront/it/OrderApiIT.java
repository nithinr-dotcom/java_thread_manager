package com.fleet.storefront.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderApiIT extends IntegrationTestSupport {

    @Autowired
    MockMvc mvc;

    private static final String BODY = """
            {"items":[{"sku":"burger","quantity":2},{"sku":"WATER","quantity":1}]}
            """;

    @Test
    void createsOrderAndReplaysItOnRetry() throws Exception {
        givenProduct("BURGER", "11.40", true, 10);
        givenProduct("WATER", "2.50", false, 10);

        MvcResult created = mvc.perform(post("/api/v1/orders").with(as(POS_KEY))
                        .header("X-Terminal-Id", "kiosk-1").header("Idempotency-Key", "8f1c")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.orderNumber").value(matchesPattern("ORD-HOSP01-\\d{8}-0001")))
                .andExpect(jsonPath("$.subtotal").value(25.30))
                .andExpect(jsonPath("$.taxTotal").value(2.09))
                .andExpect(jsonPath("$.total").value(27.39))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andReturn();

        MvcResult replay = mvc.perform(post("/api/v1/orders").with(as(POS_KEY))
                        .header("X-Terminal-Id", "kiosk-1").header("Idempotency-Key", "8f1c")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(replay.getResponse().getContentAsString()).isEqualTo(created.getResponse().getContentAsString());
        assertThat(stockOf("BURGER")).isEqualTo(8);

        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/v1/orders/{id}", id).with(as(POS_KEY))).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2));
    }

    @Test
    void outOfStockIsAConflictWithStableCode() throws Exception {
        givenProduct("BURGER", "11.40", true, 1);
        givenProduct("WATER", "2.50", false, 10);

        mvc.perform(post("/api/v1/orders").with(as(POS_KEY))
                        .header("X-Terminal-Id", "kiosk-1").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.sku").value("BURGER"));
        assertThat(stockOf("WATER")).isEqualTo(10);
    }

    @Test
    void rejectsMissingIdempotencyKeyAndBadPayloads() throws Exception {
        mvc.perform(post("/api/v1/orders").with(as(POS_KEY)).header("X-Terminal-Id", "kiosk-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").with(as(POS_KEY)).header("X-Terminal-Id", "kiosk-1").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"sku\":\"WATER\",\"quantity\":0}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").with(as(POS_KEY)).header("X-Terminal-Id", "kiosk-1").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"sku\":\"NOPE\",\"quantity\":1}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PRODUCT"));
    }
}
