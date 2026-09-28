package com.github.guoyyit.contractmockservicejava.store;

import com.github.guoyyit.contractmockservicejava.model.MockConfig;

import java.util.Map;

/**
 * One immutable, fully visible configuration revision. In-flight requests capture the
 * revision they matched, so they never observe a mix of old and new configuration.
 */
public record ActiveRevision(
        MockConfig config,
        Map<String, ScenarioRuntime> runtimes
) {
}
