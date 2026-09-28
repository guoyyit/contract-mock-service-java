package com.github.guoyyit.contractmockservicejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.contractmockservicejava.model.BodySpec;
import com.github.guoyyit.contractmockservicejava.model.EndpointContract;
import com.github.guoyyit.contractmockservicejava.model.FieldSpec;
import com.github.guoyyit.contractmockservicejava.model.JsonType;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.model.MockResponse;
import com.github.guoyyit.contractmockservicejava.model.ParamSpec;
import com.github.guoyyit.contractmockservicejava.model.RequestSpec;
import com.github.guoyyit.contractmockservicejava.model.ScenarioDef;
import com.github.guoyyit.contractmockservicejava.model.ScenarioSelector;
import com.github.guoyyit.contractmockservicejava.model.ScenarioStep;
import com.github.guoyyit.contractmockservicejava.model.SelectorSource;

import java.util.List;
import java.util.Map;

/** Builders for configs used across tests. */
public final class Fixtures {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {
    }

    public static MockResponse response(int status, String bodyJson) {
        try {
            return new MockResponse(status, Map.of(), MAPPER.readTree(bodyJson));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static MockResponse responseWithHeaders(int status, String bodyJson,
                                                   Map<String, String> headers) {
        try {
            return new MockResponse(status, headers, MAPPER.readTree(bodyJson));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static ScenarioStep step(String name, MockResponse response) {
        return new ScenarioStep(name, response, null, null);
    }

    public static ScenarioStep step(String name, MockResponse response, Long delayMs,
                                    MockResponse error) {
        return new ScenarioStep(name, response, delayMs, error);
    }

    public static ScenarioDef scenario(String name, List<ScenarioStep> steps,
                                       MockResponse fallback) {
        return new ScenarioDef(name, null, steps, fallback, null, null);
    }

    public static ScenarioDef scenarioWithSelector(String name, SelectorSource source,
                                                   String field, String value,
                                                   List<ScenarioStep> steps,
                                                   MockResponse fallback) {
        ScenarioSelector selector = new ScenarioSelector(source, field, value);
        return new ScenarioDef(name, selector, steps, fallback, null, null);
    }

    public static ScenarioDef scenarioInjected(String name, Long delayMs, MockResponse error,
                                               List<ScenarioStep> steps,
                                               MockResponse fallback) {
        return new ScenarioDef(name, null, steps, fallback, delayMs, error);
    }

    public static EndpointContract endpoint(String id, String method, String path,
                                            RequestSpec request,
                                            Map<String, ScenarioDef> scenarios,
                                            String defaultScenario) {
        return new EndpointContract(id, method, path, request, scenarios, defaultScenario);
    }

    public static RequestSpec requestSpec(List<ParamSpec> headers,
                                          List<ParamSpec> query,
                                          BodySpec body) {
        return new RequestSpec(headers, query, body);
    }

    public static ParamSpec param(String name, boolean required, JsonType type,
                                  String pattern) {
        return new ParamSpec(name, required, type, pattern);
    }

    public static FieldSpec field(String path, JsonType type, boolean required) {
        return new FieldSpec(path, type, required);
    }

    public static BodySpec bodyRequired(List<FieldSpec> fields) {
        return new BodySpec(true, "application/json", fields);
    }

    public static MockConfig config(EndpointContract... endpoints) {
        return new MockConfig(null, null, List.of(endpoints));
    }

    public static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static ObjectNode object(String text) {
        return (ObjectNode) json(text);
    }

    public static ArrayNode array(String text) {
        return (ArrayNode) json(text);
    }
}
