package com.github.guoyyit.contractmockservicejava.store;

import com.github.guoyyit.contractmockservicejava.model.Contract;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;

import java.util.List;
import java.util.Map;

/**
 * Immutable view of one configuration version plus its mutable scenario
 * counters. A request only ever sees one snapshot for its whole handling.
 */
public record Snapshot(
        MockConfig config,
        List<Contract> contracts,
        Map<String, ScenarioState> scenarioStates
) {
    public long version() {
        return config == null ? 0L : config.version();
    }
}
