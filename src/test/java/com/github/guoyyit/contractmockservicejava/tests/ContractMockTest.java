package com.github.guoyyit.contractmockservicejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers contract matching, structural validation, stateful scenarios and
 * deterministic reset behavior.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContractMockTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper om = new ObjectMapper();

    private ObjectNode step(int status, String body) {
        ObjectNode node = om.createObjectNode();
        node.put("status", status);
        node.set("body", om.getNodeFactory().textNode(body));
        node.put("delayMillis", 0);
        node.put("error", false);
        node.putNull("errorCode");
        return node;
    }

    private ObjectNode scenario(String name, String... bodies) {
        ObjectNode node = om.createObjectNode();
        node.put("name", name);
        ArrayNode steps = node.putArray("steps");
        for (String body : bodies) {
            steps.add(step(200, body));
        }
        node.put("repeatLast", false);
        return node;
    }

    private ObjectNode simpleConfig(long version) {
        ObjectNode config = om.createObjectNode();
        config.put("version", version);
        config.put("note", "test");
        ArrayNode contracts = config.putArray("contracts");

        ObjectNode users = om.createObjectNode();
        users.put("id", "get-user");
        users.put("method", "GET");
        users.put("pathTemplate", "/users/{id}");
        ArrayNode query = users.putArray("queryParams");
        ObjectNode qp = query.addObject();
        qp.put("name", "debug");
        qp.put("required", false);
        qp.putNull("pattern");
        qp.putNull("equals");
        users.putArray("headers");
        users.putNull("body");
        users.putArray("selectors");
        users.put("defaultScenario", "ok");
        ArrayNode scenarios = users.putArray("scenarios");
        scenarios.add(scenario("ok", "user-one", "user-two"));
        contracts.add(users);
        return config;
    }

    private ResultActions call(ResultActions startedActions) throws Exception {
        // Wait for the async result, then perform the container's final dispatch.
        MvcResult started = startedActions.andReturn();
        started.getAsyncResult(5000);
        return mockMvc.perform(asyncDispatch(started));
    }

    private void commit(ObjectNode config) throws Exception {
        mockMvc.perform(post("/__admin/configs")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(config)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(config.get("version").asLong()));
    }

    @Test
    void matchesPathTemplateAndReturnsStatefulSequenceThenResets() throws Exception {
        commit(simpleConfig(1));

        // Path template {id} matches; first call consumes step 1.
        call(mockMvc.perform(get("/users/42"))).andExpect(status().isOk())
                .andExpect(content().string("user-one"));
        // Second call consumes step 2.
        call(mockMvc.perform(get("/users/7"))).andExpect(status().isOk())
                .andExpect(content().string("user-two"));
        // Sequence exhausted: stable, distinguishable error.
        call(mockMvc.perform(get("/users/7"))).andExpect(status().is(500))
                .andExpect(jsonPath("$.error.code").value("scenario_exhausted"));

        // Reset makes behavior deterministic again.
        mockMvc.perform(post("/__admin/scenarios/reset")).andExpect(status().isOk());
        call(mockMvc.perform(get("/users/9"))).andExpect(status().isOk())
                .andExpect(content().string("user-one"));
    }

    @Test
    void returnsNoMatchAndMethodNotAllowed() throws Exception {
        commit(simpleConfig(2));

        call(mockMvc.perform(get("/nope"))).andExpect(status().is(404))
                .andExpect(jsonPath("$.error.code").value("no_match"));

        call(mockMvc.perform(post("/users/42"))).andExpect(status().is(405))
                .andExpect(jsonPath("$.error.code").value("method_not_allowed"));
    }

    @Test
    void validatesQueryAndBodyStructurally() throws Exception {
        ObjectNode config = simpleConfig(3);
        JsonNode getUser = config.get("contracts").get(0);
        ObjectNode qp = (ObjectNode) getUser.get("queryParams").get(0);
        qp.put("required", true);
        qp.put("pattern", "true|false");

        // Add a second POST contract with a body constraint.
        ObjectNode create = om.createObjectNode();
        create.put("id", "create-order");
        create.put("method", "POST");
        create.put("pathTemplate", "/orders");
        create.putArray("queryParams");
        create.putArray("headers");
        ObjectNode bodySpec = create.putObject("body");
        bodySpec.put("required", true);
        ArrayNode fields = bodySpec.putArray("fields");
        ObjectNode amount = fields.addObject();
        amount.put("path", "amount");
        amount.put("type", "integer");
        amount.put("required", true);
        amount.putNull("pattern");
        amount.putNull("equals");
        create.putArray("selectors");
        create.put("defaultScenario", "created");
        ArrayNode scenarios = create.putArray("scenarios");
        ObjectNode sc = scenarios.addObject();
        sc.put("name", "created");
        sc.put("repeatLast", false);
        ArrayNode steps = sc.putArray("steps");
        ObjectNode step = steps.addObject();
        step.put("status", 201);
        step.put("delayMillis", 0);
        step.put("error", false);
        step.putNull("errorCode");
        step.putNull("body");
        config.withArray("contracts").add(create);

        commit(config);

        // Missing required query -> stable invalid_query message.
        call(mockMvc.perform(get("/users/1"))).andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("invalid_query"))
                .andExpect(jsonPath("$.error.message").value("query.debug: missing required query"));
        // Pattern mismatch is distinguishable.
        call(mockMvc.perform(get("/users/1").param("debug", "maybe"))).andExpect(status().is(400))
                .andExpect(jsonPath("$.error.message", org.hamcrest.Matchers.containsString("does not match pattern")));

        // Body missing.
        call(mockMvc.perform(post("/orders"))).andExpect(status().is(400))
                .andExpect(jsonPath("$.error.code").value("invalid_body"));
        // Wrong type.
        call(mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"lots\"}"))).andExpect(status().is(400))
                .andExpect(jsonPath("$.error.message").value("body.amount: expected type integer but was string"));
        // Valid body -> 201.
        call(mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100}"))).andExpect(status().is(201));
    }
}
