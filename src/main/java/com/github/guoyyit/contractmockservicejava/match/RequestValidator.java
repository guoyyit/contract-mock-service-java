package com.github.guoyyit.contractmockservicejava.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.guoyyit.contractmockservicejava.model.BodySpec;
import com.github.guoyyit.contractmockservicejava.model.Contract;
import com.github.guoyyit.contractmockservicejava.model.ErrorCode;
import com.github.guoyyit.contractmockservicejava.model.FieldSpec;
import com.github.guoyyit.contractmockservicejava.model.MockException;
import com.github.guoyyit.contractmockservicejava.model.MockRequest;
import com.github.guoyyit.contractmockservicejava.model.ParamSpec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Structural validation of query params, headers and body fields.
 *
 * <p>Each failure carries a stable, distinguishable error code and message of
 * the form {@code <location>.<name>: <reason>}, so tests and clients can rely
 * on exact text rather than wording.
 */
@Component
public class RequestValidator {

    public void validate(RouteMatch route, MockRequest request) {
        Contract contract = route.contract();
        validateParams("query", contract.queryParams(), request.queryParams(), true);
        validateParams("header", contract.headers(), singleValueHeaders(request.headers()), false);
        validateBody(contract.body(), request.body());
    }

    private Map<String, List<String>> singleValueHeaders(Map<String, String> headers) {
        java.util.LinkedHashMap<String, List<String>> map = new java.util.LinkedHashMap<>();
        headers.forEach((k, v) -> map.put(k, List.of(v)));
        return map;
    }

    private void validateParams(String location, List<ParamSpec> specs,
                                Map<String, List<String>> actual, boolean caseSensitiveKey) {
        if (specs == null) {
            return;
        }
        for (ParamSpec spec : specs) {
            String value = lookup(actual, spec.name(), caseSensitiveKey);
            if (value == null) {
                if (spec.required()) {
                    throw error(location, spec.name(), ErrorCode.INVALID_QUERY, ErrorCode.INVALID_HEADER,
                            "missing required " + location);
                }
                continue;
            }
            if (spec.equals() != null && !spec.equals().equals(value)) {
                throw error(location, spec.name(), ErrorCode.INVALID_QUERY, ErrorCode.INVALID_HEADER,
                        "expected equals '" + spec.equals() + "' but was '" + value + "'");
            }
            if (spec.pattern() != null && !Pattern.matches(spec.pattern(), value)) {
                throw error(location, spec.name(), ErrorCode.INVALID_QUERY, ErrorCode.INVALID_HEADER,
                        "does not match pattern '" + spec.pattern() + "'");
            }
        }
    }

    private MockException error(String location, String name, ErrorCode queryCode,
                                ErrorCode headerCode, String reason) {
        ErrorCode code = "query".equals(location) ? queryCode : headerCode;
        return new MockException(code, location + "." + name + ": " + reason);
    }

    private String lookup(Map<String, List<String>> actual, String name, boolean caseSensitiveKey) {
        List<String> values = actual.get(name);
        if (values == null && !caseSensitiveKey) {
            for (Map.Entry<String, List<String>> entry : actual.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    values = entry.getValue();
                    break;
                }
            }
        }
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private void validateBody(BodySpec spec, JsonNode body) {
        if (spec == null) {
            return;
        }
        if (body == null || body.isNull()) {
            if (spec.required()) {
                throw new MockException(ErrorCode.INVALID_BODY, "body: missing required request body");
            }
            return;
        }
        if (spec.fields() == null) {
            return;
        }
        for (FieldSpec field : spec.fields()) {
            JsonNode node = navigate(body, field.path());
            if (node == null || node.isMissingNode() || node.isNull()) {
                if (field.required()) {
                    throw new MockException(ErrorCode.INVALID_BODY,
                            "body." + field.path() + ": missing required field");
                }
                continue;
            }
            if (field.type() != null && !checkType(field.type(), node)) {
                throw new MockException(ErrorCode.INVALID_BODY,
                        "body." + field.path() + ": expected type " + field.type()
                                + " but was " + jsonType(node));
            }
            if (field.equals() != null) {
                String asText = node.isTextual() ? node.asText() : node.toString();
                if (!field.equals().equals(asText)) {
                    throw new MockException(ErrorCode.INVALID_BODY,
                            "body." + field.path() + ": expected equals '" + field.equals()
                                    + "' but was '" + asText + "'");
                }
            }
            if (field.pattern() != null && !Pattern.matches(field.pattern(), node.asText())) {
                throw new MockException(ErrorCode.INVALID_BODY,
                        "body." + field.path() + ": does not match pattern '" + field.pattern() + "'");
            }
        }
    }

    private JsonNode navigate(JsonNode root, String dottedPath) {
        JsonNode current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }

    private boolean checkType(String type, JsonNode node) {
        return switch (type.toLowerCase()) {
            case "string" -> node.isTextual();
            case "number" -> node.isNumber();
            case "integer" -> node.isIntegralNumber();
            case "boolean" -> node.isBoolean();
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            default -> true;
        };
    }

    private String jsonType(JsonNode node) {
        if (node.isTextual()) {
            return "string";
        }
        if (node.isIntegralNumber()) {
            return "integer";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        return "unknown";
    }
}
