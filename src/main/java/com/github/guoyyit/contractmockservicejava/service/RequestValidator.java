package com.github.guoyyit.contractmockservicejava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.guoyyit.contractmockservicejava.model.BodySpec;
import com.github.guoyyit.contractmockservicejava.model.EndpointContract;
import com.github.guoyyit.contractmockservicejava.model.FieldSpec;
import com.github.guoyyit.contractmockservicejava.model.JsonType;
import com.github.guoyyit.contractmockservicejava.model.ParamSpec;
import com.github.guoyyit.contractmockservicejava.model.RequestSpec;
import com.github.guoyyit.contractmockservicejava.model.ValidationIssue;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Structural validation of an incoming request against its matched endpoint contract.
 *
 * <p>Every failure is reported as a {@link ValidationIssue} with a stable location
 * ({@code header}/{@code query}/{@code body}), the offending field, a machine-readable
 * reason, and expected/actual descriptions — so different failure causes stay
 * distinguishable in tests and client code. Header lookups are case-insensitive.
 */
@Service
public class RequestValidator {

    public List<ValidationIssue> validate(
            EndpointContract endpoint,
            Map<String, String> headers,
            Map<String, String> query,
            JsonNode body) {
        List<ValidationIssue> issues = new ArrayList<>();
        RequestSpec spec = endpoint.request();
        if (spec == null) {
            return issues;
        }
        validateParams("header", spec.headers(), headers, true, issues);
        validateParams("query", spec.queryParams(), query, false, issues);
        validateBody(spec.body(), body, issues);
        return issues;
    }

    private void validateParams(
            String location,
            List<ParamSpec> specs,
            Map<String, String> values,
            boolean headerLookup,
            List<ValidationIssue> issues) {
        if (specs == null) {
            return;
        }
        for (ParamSpec param : specs) {
            String raw = lookup(values, param.name(), headerLookup);
            if (raw == null) {
                if (param.required()) {
                    issues.add(new ValidationIssue(location, param.name(),
                            "missing", "present", "absent"));
                }
                continue;
            }
            if (param.type() != null && !checkType(param.type(), raw)) {
                issues.add(new ValidationIssue(location, param.name(),
                        "type_mismatch", param.type().name().toLowerCase(), describeScalar(raw)));
            }
            if (param.pattern() != null && !matchesPattern(param.pattern(), raw)) {
                issues.add(new ValidationIssue(location, param.name(),
                        "pattern_mismatch", param.pattern(), raw));
            }
        }
    }

    private void validateBody(BodySpec spec, JsonNode body, List<ValidationIssue> issues) {
        if (spec == null) {
            return;
        }
        if (body == null || body.isNull() || body.isMissingNode()) {
            if (spec.required()) {
                issues.add(new ValidationIssue("body", "$", "missing",
                        spec.contentType() != null ? spec.contentType() : "JSON object", "absent"));
            }
            return;
        }
        if (spec.fields() == null) {
            return;
        }
        for (FieldSpec field : spec.fields()) {
            JsonNode node = navigate(body, field.path());
            if (node == null || node.isMissingNode()) {
                if (field.required()) {
                    issues.add(new ValidationIssue("body", field.path(),
                            "missing", field.type() != null ? field.type().name().toLowerCase() : "present",
                            "absent"));
                }
                continue;
            }
            if (field.type() != null && !checkJsonType(field.type(), node)) {
                issues.add(new ValidationIssue("body", field.path(),
                        "type_mismatch", field.type().name().toLowerCase(), jsonTypeName(node)));
            }
        }
    }

    private String lookup(Map<String, String> values, String name, boolean caseInsensitive) {
        if (values == null) {
            return null;
        }
        if (caseInsensitive) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
        return values.get(name);
    }

    private boolean checkType(JsonType type, String raw) {
        return switch (type) {
            case STRING -> true;
            case NUMBER -> parseDouble(raw) != null;
            case INTEGER -> parseLong(raw) != null;
            case BOOLEAN -> raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false");
            default -> false;
        };
    }

    private boolean checkJsonType(JsonType type, JsonNode node) {
        return switch (type) {
            case STRING -> node.isTextual();
            case NUMBER -> node.isNumber();
            case INTEGER -> node.isIntegralNumber();
            case BOOLEAN -> node.isBoolean();
            case OBJECT -> node.isObject();
            case ARRAY -> node.isArray();
            case NULL -> node.isNull();
        };
    }

    private String describeScalar(String raw) {
        if (raw == null) {
            return "null";
        }
        if (raw.length() <= 32) {
            return raw;
        }
        return raw.substring(0, 32) + "...";
    }

    private String jsonTypeName(JsonNode node) {
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
        if (node.isNull()) {
            return "null";
        }
        return node.getNodeType().name().toLowerCase();
    }

    private Long parseLong(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean matchesPattern(String pattern, String raw) {
        try {
            return Pattern.matches(pattern, raw);
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /** Navigate a dot-separated path, supporting simple array indices (e.g. items.0.id). */
    private JsonNode navigate(JsonNode root, String path) {
        if (path == null || path.isEmpty() || "$".equals(path)) {
            return root;
        }
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            Long index = parseLong(segment);
            if (index != null) {
                current = current.path(index.intValue());
            } else {
                current = current.path(segment);
            }
            if (current.isMissingNode()) {
                return current;
            }
        }
        return current;
    }
}
