package com.github.guoyyit.contractmockservicejava.model;

import java.util.List;

/** A named, stateful scenario: ordered steps, a fallback after exhaustion and optional injections. */
public record ScenarioDef(
        String name,
        ScenarioSelector selector,
        List<ScenarioStep> steps,
        MockResponse fallback,
        Long delayMs,
        MockResponse errorResponse
) {
}
