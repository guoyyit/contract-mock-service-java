package com.github.guoyyit.contractmockservicejava.store;

import com.github.guoyyit.contractmockservicejava.match.ConfigValidator;
import com.github.guoyyit.contractmockservicejava.model.Contract;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.model.Scenario;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the current {@link Snapshot} behind a volatile reference and swaps
 * whole versions atomically. In-flight requests keep referencing the snapshot
 * they started with, so they never observe a half-old / half-new config.
 *
 * <p>Scenario counters belong to a snapshot. Publishing a new version creates
 * fresh counters, which makes behavior after an update deterministic.
 */
@Component
public class ContractStore {

    private final ConfigRepository repository;
    private final ConfigValidator validator;
    private volatile Snapshot current;

    public ContractStore(ConfigRepository repository, ConfigValidator validator) {
        this.repository = repository;
        this.validator = validator;
        this.current = build(repository.load());
    }

    private Snapshot build(MockConfig config) {
        List<Contract> contracts = config == null
                ? List.of()
                : List.copyOf(config.contracts() == null ? List.of() : config.contracts());
        Map<String, ScenarioState> states = new LinkedHashMap<>();
        for (Contract contract : contracts) {
            if (contract.scenarios() != null) {
                for (Scenario scenario : contract.scenarios()) {
                    states.put(key(contract.id(), scenario.name()), new ScenarioState());
                }
            }
        }
        return new Snapshot(config, contracts, Map.copyOf(states));
    }

    private static String key(String contractId, String scenarioName) {
        return contractId + "#@#" + scenarioName;
    }

    /** Returns the currently active snapshot (never null). */
    public Snapshot current() {
        return current;
    }

    /**
     * Validates, persists, then atomically publishes a new version. The
     * persistence happens before publication, so an uncompleted update is
     * never observable and the last fully committed set is restored on
     * restart.
     */
    public synchronized Snapshot commit(MockConfig config) {
        MockConfig validated = validator.validate(config);
        repository.save(validated);
        Snapshot snapshot = build(validated);
        current = snapshot;
        return snapshot;
    }

    /** Atomically resets all scenario counters in the current snapshot. */
    public void resetScenarios() {
        current.scenarioStates().values().forEach(ScenarioState::reset);
    }

    /** Resets a single scenario's counter if present. */
    public void resetScenario(String scenarioName) {
        String suffix = "#@#" + scenarioName;
        current.scenarioStates().forEach((key, state) -> {
            if (key.endsWith(suffix)) {
                state.reset();
            }
        });
    }

    /** Looks up the mutable state for one scenario within a snapshot. */
    public ScenarioState state(Snapshot snapshot, String contractId, String scenarioName) {
        return snapshot.scenarioStates().get(key(contractId, scenarioName));
    }
}
