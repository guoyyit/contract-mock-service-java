package com.github.guoyyit.contractmockservicejava.match;

import com.github.guoyyit.contractmockservicejava.model.Contract;
import com.github.guoyyit.contractmockservicejava.model.ErrorCode;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.model.MockException;
import com.github.guoyyit.contractmockservicejava.model.ResponseStep;
import com.github.guoyyit.contractmockservicejava.model.Scenario;
import com.github.guoyyit.contractmockservicejava.model.ScenarioSelector;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a whole {@link MockConfig} before it can be committed. A
 * configuration is only valid as a complete set, so an invalid set can
 * never replace the active snapshot.
 */
@Component
public class ConfigValidator {

    public MockConfig validate(MockConfig config) {
        if (config == null) {
            throw new MockException(ErrorCode.INVALID_CONTRACT, "config must not be null");
        }
        if (config.version() <= 0) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "config.version must be a positive integer");
        }
        List<Contract> contracts = config.contracts();
        if (contracts == null || contracts.isEmpty()) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "config.contracts must contain at least one contract");
        }
        Set<String> routes = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Contract contract : contracts) {
            validateContract(contract, ids, routes);
        }
        return config;
    }

    private void validateContract(Contract contract, Set<String> ids, Set<String> routes) {
        if (contract == null) {
            throw new MockException(ErrorCode.INVALID_CONTRACT, "contract must not be null");
        }
        if (contract.id() == null || contract.id().isBlank()) {
            throw new MockException(ErrorCode.INVALID_CONTRACT, "contract.id must not be blank");
        }
        if (!ids.add(contract.id())) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "duplicate contract id: " + contract.id());
        }
        String method = contract.method() == null ? "" : contract.method().trim().toUpperCase();
        if (!Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").contains(method)) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "contract " + contract.id() + " has unsupported method: " + contract.method());
        }
        String path = contract.pathTemplate();
        if (path == null || !path.startsWith("/")) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "contract " + contract.id() + " pathTemplate must start with '/'");
        }
        String routeKey = method + " " + path;
        if (!routes.add(routeKey)) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "duplicate route: " + routeKey);
        }
        List<Scenario> scenarios = contract.scenarios();
        if (scenarios == null || scenarios.isEmpty()) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "contract " + contract.id() + " must define at least one scenario");
        }
        Set<String> scenarioNames = new HashSet<>();
        for (Scenario scenario : scenarios) {
            validateScenario(contract, scenario, scenarioNames);
        }
        String defaultScenario = contract.defaultScenario();
        if (defaultScenario == null || defaultScenario.isBlank()) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "contract " + contract.id() + " defaultScenario must be set");
        }
        if (!scenarioNames.contains(defaultScenario)) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "contract " + contract.id() + " defaultScenario '"
                            + defaultScenario + "' is not defined");
        }
        if (contract.selectors() != null) {
            for (ScenarioSelector selector : contract.selectors()) {
                if (selector.source() == null) {
                    throw new MockException(ErrorCode.INVALID_CONTRACT,
                            "contract " + contract.id() + " selector source must not be null");
                }
                if (selector.name() == null || selector.name().isBlank()) {
                    throw new MockException(ErrorCode.INVALID_CONTRACT,
                            "contract " + contract.id() + " selector name must not be blank");
                }
                if (selector.scenario() == null
                        || !scenarioNames.contains(selector.scenario())) {
                    throw new MockException(ErrorCode.INVALID_CONTRACT,
                            "contract " + contract.id() + " selector points to unknown scenario '"
                                    + selector.scenario() + "'");
                }
            }
        }
    }

    private void validateScenario(Contract contract, Scenario scenario, Set<String> names) {
        if (scenario == null || scenario.name() == null || scenario.name().isBlank()) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "contract " + contract.id() + " has a scenario with blank name");
        }
        if (!names.add(scenario.name())) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "contract " + contract.id() + " duplicate scenario: " + scenario.name());
        }
        List<ResponseStep> steps = scenario.steps();
        if (steps == null || steps.isEmpty()) {
            throw new MockException(ErrorCode.INVALID_CONTRACT,
                    "scenario " + scenario.name() + " must define at least one step");
        }
        for (ResponseStep step : steps) {
            if (step.status() < 100 || step.status() > 599) {
                throw new MockException(ErrorCode.INVALID_CONTRACT,
                        "scenario " + scenario.name() + " has invalid status: " + step.status());
            }
            if (step.delayMillis() < 0) {
                throw new MockException(ErrorCode.INVALID_CONTRACT,
                        "scenario " + scenario.name() + " delayMillis must be >= 0");
            }
        }
    }
}
