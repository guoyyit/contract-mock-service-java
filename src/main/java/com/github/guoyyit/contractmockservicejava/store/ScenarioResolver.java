package com.github.guoyyit.contractmockservicejava.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.guoyyit.contractmockservicejava.model.EndpointContract;
import com.github.guoyyit.contractmockservicejava.model.ScenarioDef;
import com.github.guoyyit.contractmockservicejava.model.ScenarioSelector;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Selects the scenario applicable to a request and owns its call-sequence runtime.
 *
 * <p>Selection: the first scenario whose selector equals the value read from the request
 * (header / query parameter / dotted body field) wins; otherwise the endpoint's
 * configured default scenario is used (falling back to a scenario literally named
 * {@code default}). Runtimes live inside the immutable {@link ActiveRevision}, so scenario
 * state never leaks across configuration versions.
 */
@Service
public class ScenarioResolver {

    static final String DEFAULT_SCENARIO = "default";

    public ScenarioDef selectScenario(
            EndpointContract endpoint,
            Map<String, String> headers,
            Map<String, String> query,
            JsonNode body) {
        Map<String, ScenarioDef> scenarios = endpoint.scenarios();
        if (scenarios != null) {
            for (ScenarioDef scenario : scenarios.values()) {
                if (scenario == null || scenario.selector() == null) {
                    continue;
                }
                if (selectorMatches(scenario.selector(), headers, query, body)) {
                    return scenario;
                }
            }
            String named = endpoint.defaultScenario();
            if (named != null && scenarios.containsKey(named)) {
                return scenarios.get(named);
            }
            if (scenarios.containsKey(DEFAULT_SCENARIO)) {
                return scenarios.get(DEFAULT_SCENARIO);
            }
            if (!scenarios.isEmpty()) {
                return scenarios.values().iterator().next();
            }
        }
        return null;
    }

    public ScenarioRuntime runtime(ActiveRevision revision, String endpointId, String scenarioName) {
        if (revision == null) {
            return null;
        }
        return revision.runtimes().get(key(endpointId, scenarioName));
    }

    public void resetRuntime(ActiveRevision revision, String endpointId, String scenarioName) {
        if (revision == null) {
            return;
        }
        if (endpointId == null) {
            revision.runtimes().values().forEach(ScenarioRuntime::reset);
            return;
        }
        if (scenarioName == null) {
            String prefix = endpointId + ":";
            revision.runtimes().forEach((k, rt) -> {
                if (k.startsWith(prefix)) {
                    rt.reset();
                }
            });
            return;
        }
        ScenarioRuntime runtime = revision.runtimes().get(key(endpointId, scenarioName));
        if (runtime != null) {
            runtime.reset();
        }
    }

    private String key(String endpointId, String scenarioName) {
        return endpointId + ":" + scenarioName;
    }

    private boolean selectorMatches(
            ScenarioSelector selector,
            Map<String, String> headers,
            Map<String, String> query,
            JsonNode body) {
        String actual = switch (selector.source()) {
            case HEADER -> readHeader(headers, selector.field());
            case QUERY -> query != null ? query.get(selector.field()) : null;
            case BODY -> readBody(body, selector.field());
        };
        return actual != null && actual.equals(selector.equalsValue());
    }

    private String readHeader(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String readBody(JsonNode body, String path) {
        if (body == null || path == null) {
            return null;
        }
        JsonNode current = body;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.path(segment);
            if (current.isMissingNode() || current.isNull()) {
                return null;
            }
        }
        return current.isValueNode() ? current.asText() : current.toString();
    }
}
