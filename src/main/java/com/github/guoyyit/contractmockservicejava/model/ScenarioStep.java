package com.github.guoyyit.contractmockservicejava.model;

/**
 * One stateful step in a scenario sequence. {@code delayMs} and {@code errorResponse}
 * override the scenario-level injection and affect only the request consuming this step.
 */
public record ScenarioStep(
        String name,
        MockResponse response,
        Long delayMs,
        MockResponse errorResponse
) {
}
