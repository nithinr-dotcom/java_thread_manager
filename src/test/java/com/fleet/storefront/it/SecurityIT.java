package com.fleet.storefront.it;

import com.fleet.storefront.domain.terminal.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR 0010: per-device API keys, role-based access, terminal identity bound to the credential. */
@AutoConfigureObservability // tests disable metric exporters by default; we assert /actuator/prometheus
class SecurityIT extends IntegrationTestSupport {

    @Autowired
    MockMvc mvc;

    @Test
    void apiCallsNeedAValidKey() throws Exception {
        mvc.perform(get("/api/v1/catalog"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/catalog").with(as("sf_not_a_real_key")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/catalog").header("Authorization", "Basic Zm9vOmJhcg=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rolesLimitWhatADeviceMayDo() throws Exception {
        mvc.perform(get("/api/v1/ops/outbox").with(as(POS_KEY)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/inventory/X/restock").with(as(POS_KEY))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders").with(as(KITCHEN_KEY))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/kitchen/tickets").with(as(POS_KEY))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/catalog").with(as(HQ_KEY))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/0192d0a0-0000-7000-8000-000000000000/refunds").with(as(POS_KEY))
                        .header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/kitchen/tickets").with(as(KITCHEN_KEY))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ops/outbox").with(as(MANAGER_KEY))).andExpect(status().isOk());
    }

    @Test
    void aTerminalCannotActUnderAnotherTerminalsId() throws Exception {
        mvc.perform(post("/api/v1/orders").with(as(POS_KEY)).header("X-Terminal-Id", "kiosk-2")
                        .header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"sku\":\"A\",\"quantity\":1}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TERMINAL_MISMATCH"));
    }

    @Test
    void meDescribesTheDeviceAndItsBranch() throws Exception {
        mvc.perform(get("/api/v1/me").with(as(POS_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminalId").value("kiosk-1"))
                .andExpect(jsonPath("$.role").value("POS"))
                .andExpect(jsonPath("$.branch.code").value("HOSP01"))
                .andExpect(jsonPath("$.branch.currency").value("USD"));
    }

    @Test
    void registeredKeysWorkUntilRevoked_andTheKeyIsShownOnlyOnce() throws Exception {
        String body = mvc.perform(post("/api/v1/terminals").with(as(MANAGER_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalId\":\"kiosk-9\",\"name\":\"Kiosk 9\",\"role\":\"POS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").value(matchesPattern("sf_[A-Za-z0-9_-]{43}")))
                .andReturn().getResponse().getContentAsString();
        String key = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey");

        mvc.perform(get("/api/v1/me").with(as(key))).andExpect(jsonPath("$.terminalId").value("kiosk-9"));
        mvc.perform(get("/api/v1/terminals").with(as(MANAGER_KEY)))
                .andExpect(jsonPath("$[?(@.terminalId=='kiosk-9')].apiKey").isEmpty());
        mvc.perform(post("/api/v1/terminals").with(as(MANAGER_KEY)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminalId\":\"kiosk-9\",\"name\":\"Again\",\"role\":\"POS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TERMINAL_EXISTS"));

        mvc.perform(post("/api/v1/terminals/kiosk-9/revoke").with(as(MANAGER_KEY)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/me").with(as(key))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/terminals/it-manager/revoke").with(as(MANAGER_KEY)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANNOT_REVOKE_SELF"));
    }

    @Test
    void healthIsPublic_metricsAreForManagers_andEveryResponseCarriesARequestId() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(as(POS_KEY))).andExpect(status().isForbidden());
        givenDevice("prometheus", Role.MONITOR, "sf_it_monitor_key");
        mvc.perform(get("/actuator/prometheus").with(as("sf_it_monitor_key"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders").with(as("sf_it_monitor_key"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health").with(as("sf_it_monitor_key"))).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus").with(as(MANAGER_KEY))).andExpect(status().isOk())
                .andExpect(content().string(containsString("storefront_outbox_pending")));

        mvc.perform(get("/api/v1/me").with(as(POS_KEY)).header("X-Request-Id", "trace-123"))
                .andExpect(header().string("X-Request-Id", "trace-123"));
        mvc.perform(get("/api/v1/me").with(as(POS_KEY)))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }
}
