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
 * A configured delay applies only to the targeted request and must not block
 * or otherwise affect other requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DelayIsolationTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper om = new ObjectMapper();

    private ResultActions call(ResultActions startedActions) throws Exception {
        MvcResult started = startedActions.andReturn();
        started.getAsyncResult(5000);
        return mockMvc.perform(asyncDispatch(started));
    }

    @Test
    void delayedScenarioDoesNotBlockOtherRequests() throws Exception {
        ObjectNode config = om.createObjectNode();
        config.put("version", 21);
        ArrayNode contracts = config.putArray("contracts");
        ObjectNode c = om.createObjectNode();
        c.put("id", "work");
        c.put("method", "POST");
        c.put("pathTemplate", "/work");
        c.putArray("queryParams");
        c.putArray("headers");
        c.putNull("body");
        ArrayNode selectors = c.putArray("selectors");
        ObjectNode sel = selectors.addObject();
        sel.put("source", "HEADER");
        sel.put("name", "X-Slow");
        sel.put("equals", "yes");
        sel.put("scenario", "slow");
        c.put("defaultScenario", "fast");
        ArrayNode scenarios = c.putArray("scenarios");

        ObjectNode fast = scenarios.addObject();
        fast.put("name", "fast");
        fast.put("repeatLast", true);
        ObjectNode fastStep = fast.putArray("steps").addObject();
        fastStep.put("status", 200);
        fastStep.put("body", "fast-ok");
        fastStep.put("delayMillis", 0);
        fastStep.put("error", false);
        fastStep.putNull("errorCode");

        ObjectNode slow = scenarios.addObject();
        slow.put("name", "slow");
        slow.put("repeatLast", true);
        ObjectNode slowStep = slow.putArray("steps").addObject();
        slowStep.put("status", 200);
        slowStep.put("body", "slow-ok");
        slowStep.put("delayMillis", 1200);
        slowStep.put("error", false);
        slowStep.putNull("errorCode");

        contracts.add(c);
        mockMvc.perform(post("/__admin/configs").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(config))).andExpect(status().isOk());

        long begin = System.currentTimeMillis();
        // Several "fast" requests while no slow request is pending.
        for (int i = 0; i < 5; i++) {
            call(mockMvc.perform(post("/work")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$").value("fast-ok"));
        }
        long fastElapsed = System.currentTimeMillis() - begin;
        // Five immediate requests must not accumulate the slow delay.
        org.junit.jupiter.api.Assertions.assertTrue(fastElapsed < 800,
                "fast requests were blocked by delay config, elapsed=" + fastElapsed);

        // The slow request honors its own delay and returns its own body.
        long slowBegin = System.currentTimeMillis();
        call(mockMvc.perform(post("/work").header("X-Slow", "yes")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").value("slow-ok"));
        long slowElapsed = System.currentTimeMillis() - slowBegin;
        org.junit.jupiter.api.Assertions.assertTrue(slowElapsed >= 1000,
                "slow request should honor its delay, elapsed=" + slowElapsed);

        // After the slow call, fast requests are still immediate and unaffected.
        call(mockMvc.perform(post("/work")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").value("fast-ok"));
    }
}
