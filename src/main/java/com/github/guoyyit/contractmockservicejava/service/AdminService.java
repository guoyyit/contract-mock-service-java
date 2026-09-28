package com.github.guoyyit.contractmockservicejava.service;

import com.github.guoyyit.contractmockservicejava.model.EndpointContract;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.store.ActiveRevision;
import com.github.guoyyit.contractmockservicejava.store.ConfigPersistenceService;
import com.github.guoyyit.contractmockservicejava.store.ContractStore;
import com.github.guoyyit.contractmockservicejava.store.ScenarioResolver;
import com.github.guoyyit.contractmockservicejava.store.ScenarioRuntime;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Administrative operations on the whole configuration group.
 *
 * <p>A commit is all-or-nothing: the candidate is validated as a group, stamped with a new
 * monotonically increasing version, durably persisted <em>first</em>, and only then
 * published through a single atomic store swap. Requests in flight against the previous
 * revision keep the old config and old scenario state; new requests see the complete new
 * group with freshly zeroed scenario state. Synchronized commits serialize against each
 * other so versions cannot be lost under concurrent updates.
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final ContractStore store;
    private final ConfigPersistenceService persistence;
    private final ConfigValidator validator;
    private final ScenarioResolver scenarioResolver;

    public AdminService(
            ContractStore store,
            ConfigPersistenceService persistence,
            ConfigValidator validator,
            ScenarioResolver scenarioResolver) {
        this.store = store;
        this.persistence = persistence;
        this.validator = validator;
        this.scenarioResolver = scenarioResolver;
    }

    /** Restore the most recently committed configuration at startup. */
    @PostConstruct
    public void restore() {
        MockConfig restored = persistence.load();
        if (restored == null || restored.endpoints() == null || restored.endpoints().isEmpty()) {
            store.replace(buildRevision(ConfigPersistenceService.emptyConfig()));
            return;
        }
        if (!validator.validate(restored).isEmpty()) {
            log.warn("Persisted configuration version {} failed validation; starting empty",
                    restored.version());
            store.replace(buildRevision(ConfigPersistenceService.emptyConfig()));
            return;
        }
        store.replace(buildRevision(restored));
        log.info("Activated committed configuration version {}", restored.version());
    }

    public ActiveRevision current() {
        ActiveRevision revision = store.current();
        if (revision == null) {
            // Defensive: restore() always publishes, but never hand out null.
            revision = buildRevision(ConfigPersistenceService.emptyConfig());
            store.replace(revision);
        }
        return revision;
    }

    public synchronized ActiveRevision commit(MockConfig candidate) {
        List<String> errors = validator.validate(candidate);
        if (!errors.isEmpty()) {
            throw new ServiceException("INVALID_CONFIG",
                    "Configuration rejected: " + String.join("; ", errors),
                    HttpStatus.BAD_REQUEST);
        }
        long previousVersion = current().config().version() == null
                ? 0L : current().config().version();
        MockConfig stamped = new MockConfig(
                previousVersion + 1,
                Instant.now().toString(),
                List.copyOf(candidate.endpoints()));

        // Durable first: only a fully written file can become the active group.
        persistence.save(stamped);
        ActiveRevision revision = buildRevision(stamped);
        store.replace(revision);
        log.info("Committed configuration version {} with {} endpoint(s)",
                stamped.version(), stamped.endpoints().size());
        return revision;
    }

    public ActiveRevision resetScenarios(String endpointId, String scenarioName) {
        ActiveRevision revision = current();
        if (endpointId != null && endpointExists(revision, endpointId) == null) {
            throw new ServiceException("ENDPOINT_NOT_FOUND",
                    "No endpoint with id '" + endpointId + "'", HttpStatus.NOT_FOUND);
        }
        scenarioResolver.resetRuntime(revision, endpointId, scenarioName);
        return revision;
    }

    private EndpointContract endpointExists(ActiveRevision revision, String endpointId) {
        return revision.config().endpoints().stream()
                .filter(e -> endpointId.equals(e.id()))
                .findFirst()
                .orElse(null);
    }

    private ActiveRevision buildRevision(MockConfig config) {
        Map<String, ScenarioRuntime> runtimes = new LinkedHashMap<>();
        for (EndpointContract endpoint : config.endpoints()) {
            for (String scenarioName : endpoint.scenarios().keySet()) {
                runtimes.put(endpoint.id() + ":" + scenarioName, new ScenarioRuntime());
            }
        }
        return new ActiveRevision(config, runtimes);
    }

}
