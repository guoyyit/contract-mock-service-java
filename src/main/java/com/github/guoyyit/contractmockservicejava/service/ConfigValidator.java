package com.github.guoyyit.contractmockservicejava.service;

import com.github.guoyyit.contractmockservicejava.model.EndpointContract;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.model.ScenarioDef;
import org.springframework.stereotype.Service;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a configuration group before it is committed.
 *
 * <p>A group is only accepted as a whole: any error rejects the entire update, leaving the
 * previously committed revision untouched. Endpoint ids must be present, method+path
 * pairs unique, path templates parseable, scenario names unique and configured defaults
 * must exist.
 */
@Service
public class ConfigValidator {

    private static final Set<String> METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    private final PathPatternParser parser = PathPatternParser.defaultInstance;

    public List<String> validate(MockConfig config) {
        List<String> errors = new ArrayList<>();
        if (config == null) {
            errors.add("config must not be null");
            return errors;
        }
        if (config.endpoints() == null) {
            errors.add("endpoints must not be null");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        Set<String> methodPaths = new HashSet<>();

        for (int i = 0; i < config.endpoints().size(); i++) {
            EndpointContract endpoint = config.endpoints().get(i);
            String where = "endpoints[" + i + "]";
            if (endpoint == null) {
                errors.add(where + ": endpoint must not be null");
                continue;
            }
            String id = endpoint.id();
            if (id == null || id.isBlank()) {
                errors.add(where + ": id is required");
            } else if (!ids.add(id)) {
                errors.add(where + ": duplicate endpoint id '" + id + "'");
            }
            String method = endpoint.method();
            if (method == null || !METHODS.contains(method.toUpperCase())) {
                errors.add(where + " (" + id + "): unsupported method '" + method + "'");
            }
            String path = endpoint.pathTemplate();
            if (path == null || !path.startsWith("/")) {
                errors.add(where + " (" + id + "): pathTemplate must start with '/'");
            } else {
                try {
                    parser.parse(path);
                } catch (RuntimeException e) {
                    errors.add(where + " (" + id + "): invalid pathTemplate '" + path + "': "
                            + e.getMessage());
                }
            }
            if (method != null && path != null
                    && !methodPaths.add(method.toUpperCase() + " " + path)) {
                errors.add(where + " (" + id + "): duplicate method+path '"
                        + method.toUpperCase() + " " + path + "'");
            }
            validateScenarios(where, id, endpoint, errors);
        }
        return errors;
    }

    private void validateScenarios(
            String where, String id, EndpointContract endpoint, List<String> errors) {
        Map<String, ScenarioDef> scenarios = endpoint.scenarios();
        if (scenarios == null || scenarios.isEmpty()) {
            errors.add(where + " (" + id + "): at least one scenario is required");
            return;
        }
        for (Map.Entry<String, ScenarioDef> entry : scenarios.entrySet()) {
            String name = entry.getKey();
            ScenarioDef scenario = entry.getValue();
            if (scenario == null) {
                errors.add(where + " (" + id + "): scenario '" + name + "' must not be null");
                continue;
            }
            if (scenario.name() == null || !scenario.name().equals(name)) {
                errors.add(where + " (" + id + "): scenario map key '" + name
                        + "' must equal scenario.name '" + scenario.name() + "'");
            }
            boolean hasSteps = scenario.steps() != null && !scenario.steps().isEmpty();
            if (!hasSteps && scenario.fallback() == null && scenario.errorResponse() == null) {
                errors.add(where + " (" + id + "): scenario '" + name
                        + "' must define steps, a fallback or an errorResponse");
            }
            if (scenario.delayMs() != null && scenario.delayMs() < 0) {
                errors.add(where + " (" + id + "): scenario '" + name
                        + "' delayMs must be >= 0");
            }
        }
        String defaultScenario = endpoint.defaultScenario();
        if (defaultScenario != null && !scenarios.containsKey(defaultScenario)) {
            errors.add(where + " (" + id + "): defaultScenario '" + defaultScenario
                    + "' is not defined");
        }
    }
}
