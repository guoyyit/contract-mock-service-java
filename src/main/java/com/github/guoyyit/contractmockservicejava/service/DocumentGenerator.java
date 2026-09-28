package com.github.guoyyit.contractmockservicejava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.contractmockservicejava.model.BodySpec;
import com.github.guoyyit.contractmockservicejava.model.EndpointContract;
import com.github.guoyyit.contractmockservicejava.model.FieldSpec;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.model.MockResponse;
import com.github.guoyyit.contractmockservicejava.model.ParamSpec;
import com.github.guoyyit.contractmockservicejava.model.RequestSpec;
import com.github.guoyyit.contractmockservicejava.model.ScenarioDef;
import com.github.guoyyit.contractmockservicejava.model.ScenarioSelector;
import com.github.guoyyit.contractmockservicejava.model.ScenarioStep;
import com.github.guoyyit.contractmockservicejava.store.ActiveRevision;
import org.springframework.stereotype.Service;

/**
 * Produces API documentation from the active configuration only — nothing is hard-coded,
 * so documentation and served responses can never drift apart. The document covers the
 * configuration version, each path, its request constraints, every scenario's response
 * sequence/fallback, configured delays and injected errors, and the shared error codes.
 */
@Service
public class DocumentGenerator {

    private final ObjectMapper objectMapper;

    public DocumentGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode generate(ActiveRevision revision) {
        MockConfig config = revision.config();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", config.version());
        root.put("committedAt", config.committedAt());
        root.put("generatedFrom", "active-configuration");

        ArrayNode endpoints = root.putArray("endpoints");
        for (EndpointContract endpoint : config.endpoints()) {
            endpoints.add(describeEndpoint(endpoint));
        }

        root.set("errorCodes", errorCodes());
        return root;
    }

    private ObjectNode describeEndpoint(EndpointContract endpoint) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", endpoint.id());
        node.put("method", endpoint.method().toUpperCase());
        node.put("path", endpoint.pathTemplate());
        node.put("defaultScenario", endpoint.defaultScenario());
        node.set("request", describeRequest(endpoint.request()));

        ArrayNode scenarios = node.putArray("scenarios");
        if (endpoint.scenarios() != null) {
            endpoint.scenarios().forEach((name, scenario) ->
                    scenarios.add(describeScenario(name, scenario)));
        }
        return node;
    }

    private ObjectNode describeRequest(RequestSpec spec) {
        ObjectNode node = objectMapper.createObjectNode();
        if (spec == null) {
            node.set("headers", objectMapper.createArrayNode());
            node.set("queryParams", objectMapper.createArrayNode());
            node.set("body", objectMapper.createObjectNode());
            return node;
        }
        node.set("headers", describeParams(spec.headers()));
        node.set("queryParams", describeParams(spec.queryParams()));
        node.set("body", describeBody(spec.body()));
        return node;
    }

    private ArrayNode describeParams(Iterable<ParamSpec> params) {
        ArrayNode array = objectMapper.createArrayNode();
        if (params != null) {
            for (ParamSpec param : params) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("name", param.name());
                item.put("required", param.required());
                if (param.type() != null) {
                    item.put("type", param.type().name().toLowerCase());
                }
                if (param.pattern() != null) {
                    item.put("pattern", param.pattern());
                }
                array.add(item);
            }
        }
        return array;
    }

    private ObjectNode describeBody(BodySpec body) {
        ObjectNode node = objectMapper.createObjectNode();
        if (body == null) {
            return node;
        }
        node.put("required", body.required());
        if (body.contentType() != null) {
            node.put("contentType", body.contentType());
        }
        ArrayNode fields = node.putArray("fields");
        if (body.fields() != null) {
            for (FieldSpec field : body.fields()) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("path", field.path());
                item.put("required", field.required());
                if (field.type() != null) {
                    item.put("type", field.type().name().toLowerCase());
                }
                fields.add(item);
            }
        }
        return node;
    }

    private ObjectNode describeScenario(String name, ScenarioDef scenario) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        if (scenario.selector() != null) {
            node.set("selector", describeSelector(scenario.selector()));
        }
        if (scenario.delayMs() != null) {
            node.put("delayMs", scenario.delayMs());
        }
        if (scenario.errorResponse() != null) {
            node.set("errorResponse", describeResponse(scenario.errorResponse()));
        }
        ArrayNode steps = node.putArray("steps");
        if (scenario.steps() != null) {
            for (int i = 0; i < scenario.steps().size(); i++) {
                ScenarioStep step = scenario.steps().get(i);
                ObjectNode item = objectMapper.createObjectNode();
                item.put("index", i);
                if (step.name() != null) {
                    item.put("name", step.name());
                }
                item.set("response", describeResponse(step.response()));
                if (step.delayMs() != null) {
                    item.put("delayMs", step.delayMs());
                }
                if (step.errorResponse() != null) {
                    item.set("errorResponse", describeResponse(step.errorResponse()));
                }
                steps.add(item);
            }
        }
        if (scenario.fallback() != null) {
            node.set("fallback", describeResponse(scenario.fallback()));
        }
        return node;
    }

    private ObjectNode describeSelector(ScenarioSelector selector) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("source", selector.source().name().toLowerCase());
        node.put("field", selector.field());
        node.put("equals", selector.equalsValue());
        return node;
    }

    private ObjectNode describeResponse(MockResponse response) {
        ObjectNode node = objectMapper.createObjectNode();
        if (response == null) {
            return node;
        }
        node.put("status", response.status());
        if (response.headers() != null && !response.headers().isEmpty()) {
            node.set("headers", objectMapper.valueToTree(response.headers()));
        }
        JsonNode body = response.body();
        if (body != null && !body.isNull()) {
            node.set("body", body);
            node.set("bodySchema", inferSchema(body));
        }
        return node;
    }

    private ObjectNode inferSchema(JsonNode body) {
        ObjectNode schema = objectMapper.createObjectNode();
        if (body.isObject()) {
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            body.fields().forEachRemaining(entry ->
                    properties.set(entry.getKey(), inferSchema(entry.getValue())));
        } else if (body.isArray()) {
            schema.put("type", "array");
            if (!body.isEmpty()) {
                schema.set("items", inferSchema(body.get(0)));
            }
        } else if (body.isIntegralNumber()) {
            schema.put("type", "integer");
        } else if (body.isNumber()) {
            schema.put("type", "number");
        } else if (body.isBoolean()) {
            schema.put("type", "boolean");
        } else if (body.isTextual()) {
            schema.put("type", "string");
        } else if (body.isNull()) {
            schema.put("type", "null");
        } else {
            schema.put("type", body.getNodeType().name().toLowerCase());
        }
        return schema;
    }

    private ArrayNode errorCodes() {
        ArrayNode errors = objectMapper.createArrayNode();
        errors.add(error("VALIDATION_FAILED", 400,
                "Request structure violates the contract; issues[] pinpoints each field."));
        errors.add(error("ENDPOINT_NOT_FOUND", 404, "No mock contract matches method+path."));
        errors.add(error("METHOD_NOT_ALLOWED", 405,
                "Path exists but method does not; Allow header lists supported methods."));
        errors.add(error("REQUEST_TIMEOUT", 504, "Client timeout/abort while a scenario delay ran."));
        errors.add(error("NO_SCENARIO", 500, "Matched endpoint exposes no scenario."));
        errors.add(error("NO_RESPONSE_DEFINED", 500,
                "Scenario step exhausted with neither steps nor a fallback response."));
        errors.add(error("INVALID_CONFIG", 400, "Submitted configuration group failed validation."));
        errors.add(error("ENDPOINT_NOT_FOUND", 404, "Reset referenced an unknown endpoint id."));
        return errors;
    }

    private ObjectNode error(String code, int status, String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("code", code);
        node.put("status", status);
        node.put("description", description);
        return node;
    }
}
