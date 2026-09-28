package com.github.guoyyit.contractmockservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers scenario selection via header/query/body and per-target delay and
 * injected error behavior.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScenarioSelectionTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper om = new ObjectMapper();

    private ObjectNode step(int status, String body, String errorCode, boolean error, long delay) {
        ObjectNode node = om.createObjectNode();
        node.put("status", status);
        if (body != null) {
            node.put("body", body);
        } else {
            node.putNull("body");
        }
        node.put("delayMillis", delay);
        node.put("error", error);
        if (errorCode == null) {
            node.putNull("errorCode");
        } else {
            node.put("errorCode", errorCode);
        }
        return node;
    }

    private ObjectNode errorStep(int status, String code) {
        ObjectNode node = step(status, null, code, true, 0);
        ObjectNode body = node.putObject("body");
        ObjectNode envelope = body.putObject("error");
        envelope.put("code", code);
        envelope.put("message", "injected " + code);
        return node;
    }

    private ResultActions call(ResultActions startedActions) throws Exception {
        MvcResult started = startedActions.andReturn();
        started.getAsyncResult(5000);
        return mockMvc.perform(asyncDispatch(started));
    }

    @Test
    void selectsScenarioByHeaderQueryAndBodyAndInjectsErrorOnlyOnTarget() throws Exception {
        ObjectNode config = om.createObjectNode();
        config.put("version", 11);
        ArrayNode contracts = config.putArray("contracts");

        ObjectNode pay = om.createObjectNode();
        pay.put("id", "pay");
        pay.put("method", "POST");
        pay.put("pathTemplate", "/pay");
        pay.putArray("queryParams");
        pay.putArray("headers");
        pay.putNull("body");
        ArrayNode selectors = pay.putArray("selectors");

        ObjectNode byHeader = selectors.addObject();
        byHeader.put("source", "HEADER");
        byHeader.put("name", "X-Mode");
        byHeader.put("equals", "fail");
        byHeader.put("scenario", "fail");

        ObjectNode byBody = selectors.addObject();
        byBody.put("source", "BODY");
        byBody.put("name", "channel");
        byBody.put("equals", "vip");
        byBody.put("scenario", "vip");

        pay.put("defaultScenario", "normal");
        ArrayNode scenarios = pay.putArray("scenarios");

        ObjectNode normal = scenarios.addObject();
        normal.put("name", "normal");
        normal.put("repeatLast", true);
        normal.putArray("steps").add(step(200, "normal-ok", null, false, 0));

        ObjectNode vip = scenarios.addObject();
        vip.put("name", "vip");
        vip.put("repeatLast", true);
        vip.putArray("steps").add(step(200, "vip-ok", null, false, 0));

        ObjectNode fail = scenarios.addObject();
        fail.put("name", "fail");
        fail.put("repeatLast", true);
        fail.putArray("steps").add(errorStep(503, "upstream_unavailable"));

        contracts.add(pay);

        mockMvc.perform(post("/__admin/configs").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(config))).andExpect(status().isOk());

        // Default scenario by header absence / non-vip body.
        call(mockMvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"web\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").value("normal-ok"));

        // Body selector chooses vip scenario.
        call(mockMvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"vip\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").value("vip-ok"));

        // Header selector chooses fail scenario; injected error response only.
        call(mockMvc.perform(post("/pay").header("X-Mode", "fail")
                .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"web\"}")))
                .andExpect(status().is(503))
                .andExpect(jsonPath("$.error.code").value("upstream_unavailable"));

        // Failure did not pollute other requests: normal still works.
        call(mockMvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"web\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").value("normal-ok"));
    }

    @Test
    void selectsScenarioByQueryParameter() throws Exception {
        ObjectNode config = om.createObjectNode();
        config.put("version", 12);
        ArrayNode contracts = config.putArray("contracts");
        ObjectNode greet = om.createObjectNode();
        greet.put("id", "greet");
        greet.put("method", "GET");
        greet.put("pathTemplate", "/greet");
        greet.putArray("queryParams");
        greet.putArray("headers");
        greet.putNull("body");
        ArrayNode selectors = greet.putArray("selectors");
        ObjectNode byQuery = selectors.addObject();
        byQuery.put("source", "QUERY");
        byQuery.put("name", "lang");
        byQuery.put("equals", "fr");
        byQuery.put("scenario", "fr");
        greet.put("defaultScenario", "en");
        ArrayNode scenarios = greet.putArray("scenarios");
        ObjectNode en = scenarios.addObject();
        en.put("name", "en");
        en.put("repeatLast", true);
        en.putArray("steps").add(step(200, "hello", null, false, 0));
        ObjectNode fr = scenarios.addObject();
        fr.put("name", "fr");
        fr.put("repeatLast", true);
        fr.putArray("steps").add(step(200, "bonjour", null, false, 0));
        contracts.add(greet);

        mockMvc.perform(post("/__admin/configs").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(config))).andExpect(status().isOk());

        call(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/greet")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").value("hello"));
        call(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/greet").param("lang", "fr")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").value("bonjour"));
    }
}
