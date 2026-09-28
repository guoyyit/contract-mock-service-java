package com.github.guoyyit.contractmockservicejava.model;

import java.util.List;

/**
 * Interface contract: method + path template, request constraints and
 * stateful scenarios.
 */
public record Contract(
        String id,
        String method,
        String pathTemplate,
        List<ParamSpec> queryParams,
        List<ParamSpec> headers,
        BodySpec body,
        List<ScenarioSelector> selectors,
        String defaultScenario,
        List<Scenario> scenarios
) {
}
