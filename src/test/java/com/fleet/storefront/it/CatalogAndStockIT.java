package com.fleet.storefront.it;

import com.fleet.storefront.domain.shared.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Catalog maintenance, HQ sync and the stock journal. */
class CatalogAndStockIT extends IntegrationTestSupport {

    @Autowired
    MockMvc mvc;

    @Test
    void managersCreateAndUpdateProducts_withOptimisticConcurrency_andInactiveProductsCannotBeSold() throws Exception {
        mvc.perform(post("/api/v1/catalog/products").with(as(MANAGER_KEY)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"soup-day","name":"Soup","unitPrice":5.90,"taxRatePercent":8.25,
                                 "requiresPreparation":true,"station":"kitchen","category":"Mains"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SOUP-DAY"))
                .andExpect(jsonPath("$.onHand").value(0));
        long version = jdbc.sql("SELECT version FROM product WHERE sku = 'SOUP-DAY'").query(Long.class).single();

        update("SOUP-DAY", "6.20", true, version).andExpect(status().isOk()).andExpect(jsonPath("$.unitPrice").value(6.20));
        update("SOUP-DAY", "6.50", true, version).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").isNumber());
        update("SOUP-DAY", "6.505", true, currentVersion("SOUP-DAY")).andExpect(status().isBadRequest());

        update("SOUP-DAY", "6.20", false, currentVersion("SOUP-DAY")).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/catalog").with(as(POS_KEY))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/catalog").param("includeInactive", "true").with(as(MANAGER_KEY)))
                .andExpect(jsonPath("$.length()").value(1));
        restock("SOUP-DAY", "{\"quantity\":5}");
        mvc.perform(post("/api/v1/orders").with(as(POS_KEY)).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"sku\":\"SOUP-DAY\",\"quantity\":1}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"));
    }

    @Test
    void aRetriedRestockWithTheSameKeyNeverAddsStockTwice() throws Exception {
        givenProduct("WATER", "2.50", false, 0);
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/inventory/WATER/restock").with(as(MANAGER_KEY)).header("Idempotency-Key", "delivery-7")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":50}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Idempotent-Replayed", i == 0 ? "false" : "true"))
                    .andExpect(jsonPath("$.onHand").value(50));
        }
        mvc.perform(post("/api/v1/inventory/WATER/restock").with(as(MANAGER_KEY))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":50}"))
                .andExpect(status().isBadRequest());
        assertThat(stockOf("WATER")).isEqualTo(50);
    }

    @Test
    void priceChangesNeverAlterIssuedOrders() throws Exception {
        givenProduct("TEA", "2.00", false, 10);
        String id = placeOrder.placeOrder(order("kiosk-1", "k1", "TEA", 1)).order().id().toString();
        update("TEA", "9.99", true, currentVersion("TEA")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/orders/{id}", id).with(as(POS_KEY)))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(2.00));
    }

    @Test
    void hqPushIsLastWriterWinsOnVersion() throws Exception {
        UUID teaId = givenProduct("TEA", "2.00", false, 10);
        jdbc.sql("UPDATE product SET version = 100 WHERE sku = 'TEA'").update();
        String batch = """
                {"products":[
                  {"productId":"%s","sku":"TEA","name":"Green tea","unitPrice":2.40,"currency":"USD","taxRatePercent":8.25,
                   "requiresPreparation":false,"station":"BAR","category":"Drinks","active":true,"version":200},
                  {"productId":"%s","sku":"TEA","name":"Stale","unitPrice":1.00,"currency":"USD","taxRatePercent":8.25,
                   "requiresPreparation":false,"active":true,"version":150},
                  {"productId":"0192d0a0-0000-7000-8000-0000000000ff","sku":"SCONE","name":"Scone","unitPrice":3.10,
                   "currency":"USD","taxRatePercent":8.25,"requiresPreparation":false,"active":true,"version":200}]}
                """.formatted(teaId, teaId);

        mvc.perform(put("/api/v1/sync/catalog").with(as(POS_KEY)).contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/sync/catalog").with(as(HQ_KEY)).contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.skipped").value(1));
        assertThat(jdbc.sql("SELECT name FROM product WHERE sku = 'TEA'").query(String.class).single()).isEqualTo("Green tea");
        assertThat(count("product")).isEqualTo(2);

        mvc.perform(put("/api/v1/sync/catalog").with(as(HQ_KEY)).contentType(MediaType.APPLICATION_JSON)
                        .content(batch.replace("\"version\":200}]", "\"version\":300}]").replace("\"currency\":\"USD\"", "\"currency\":\"EUR\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
    }

    @Test
    void everyStockChangeIsJournaled_andWasteNeverGoesBelowZero() throws Exception {
        givenProduct("MEALBOX", "8.95", false, 0);
        restock("MEALBOX", "{\"quantity\":10,\"note\":\"Delivery #1\"}").andExpect(jsonPath("$.onHand").value(10));
        placeOrder.placeOrder(order("kiosk-1", "k1", "MEALBOX", 3));
        adjust("MEALBOX", "{\"delta\":-2,\"reason\":\"WASTE\",\"note\":\"Expired\"}").andExpect(jsonPath("$.onHand").value(5));
        adjust("MEALBOX", "{\"delta\":-6,\"reason\":\"WASTE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"));
        adjust("MEALBOX", "{\"delta\":1,\"reason\":\"SALE\"}").andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/inventory/MEALBOX/movements").with(as(MANAGER_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].reason").value("WASTE"))
                .andExpect(jsonPath("$[0].onHandAfter").value(5))
                .andExpect(jsonPath("$[1].reason").value("SALE"))
                .andExpect(jsonPath("$[1].delta").value(-3))
                .andExpect(jsonPath("$[1].terminalId").value("kiosk-1"))
                .andExpect(jsonPath("$[2].reason").value("RESTOCK"))
                .andExpect(jsonPath("$[2].note").value("Delivery #1"));
        // The journal always explains the stock on hand.
        assertThat(jdbc.sql("SELECT sum(delta) FROM stock_movement").query(Long.class).single()).isEqualTo(stockOf("MEALBOX"));
    }

    private ResultActions update(String sku, String price, boolean active, long version) throws Exception {
        return mvc.perform(put("/api/v1/catalog/products/{sku}", sku).with(as(MANAGER_KEY))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"%s item","unitPrice":%s,"taxRatePercent":8.25,"requiresPreparation":false,
                         "active":%s,"version":%d}""".formatted(sku, price, active, version)));
    }

    private long currentVersion(String sku) {
        return jdbc.sql("SELECT version FROM product WHERE sku = :sku").param("sku", sku).query(Long.class).single();
    }

    private ResultActions restock(String sku, String body) throws Exception {
        return mvc.perform(post("/api/v1/inventory/{sku}/restock", sku).with(as(MANAGER_KEY))
                .header("Idempotency-Key", UuidV7.next().toString()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions adjust(String sku, String body) throws Exception {
        return mvc.perform(post("/api/v1/inventory/{sku}/adjustments", sku).with(as(MANAGER_KEY))
                .header("Idempotency-Key", UuidV7.next().toString()).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
