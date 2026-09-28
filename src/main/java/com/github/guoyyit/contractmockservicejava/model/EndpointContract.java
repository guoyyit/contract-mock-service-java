package com.github.guoyyit.contractmockservicejava.model;

import java.util.Map;

/** Contract for one mocked endpoint: method + path template, request constraints and scenarios. */
public record EndpointContract(
        String id,
        String method,
        String pathTemplate,
        RequestSpec request,
        Map<String, ScenarioDef> scenarios,
        String defaultScenario
) {
}
