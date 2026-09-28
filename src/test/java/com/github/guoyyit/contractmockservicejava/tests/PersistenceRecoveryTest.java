package com.github.guoyyit.contractmockservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.contractmockservicejava.match.ConfigValidator;
import com.github.guoyyit.contractmockservicejava.match.RequestMatcher;
import com.github.guoyyit.contractmockservicejava.match.RequestValidator;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.store.ConfigRepository;
import com.github.guoyyit.contractmockservicejava.store.ContractStore;
import com.github.guoyyit.contractmockservicejava.store.FileConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restart (a fresh {@link ContractStore} pointed at the same file) must
 * restore the last fully committed configuration and nothing else.
 */
class PersistenceRecoveryTest {

    private final ObjectMapper om = new ObjectMapper();

    private ObjectNode config(long version, String body) {
        ObjectNode config = om.createObjectNode();
        config.put("version", version);
        ArrayNode contracts = config.putArray("contracts");
        ObjectNode c = contracts.addObject();
        c.put("id", "ping-" + version);
        c.put("method", "GET");
        c.put("pathTemplate", "/ping-" + version);
        c.putArray("queryParams");
        c.putArray("headers");
        c.putNull("body");
        c.putArray("selectors");
        c.put("defaultScenario", "s");
        ObjectNode scenario = c.putArray("scenarios").addObject();
        scenario.put("name", "s");
        scenario.put("repeatLast", true);
        ObjectNode step = scenario.putArray("steps").addObject();
        step.put("status", 200);
        step.put("body", body);
        step.put("delayMillis", 0);
        step.put("error", false);
        step.putNull("errorCode");
        return config;
    }

    private ContractStore newStore(Path file) {
        FileConfigRepository repository = new FileConfigRepository(file.toString());
        return new ContractStore(repository, new ConfigValidator());
    }

    @Test
    void restoresLastCommittedVersionOnRestart(@TempDir Path dir) {
        Path file = dir.resolve("mock.json");
        ContractStore first = newStore(file);
        first.commit(om.convertValue(config(1, "v1"), MockConfig.class));
        first.commit(om.convertValue(config(2, "v2"), MockConfig.class));
        first.commit(om.convertValue(config(3, "v3"), MockConfig.class));

        // Simulate a process restart: brand new repository + store.
        ContractStore restarted = newStore(file);
        assertEquals(3L, restarted.current().version());
        assertEquals("ping-3", restarted.current().contracts().get(0).id());
        // No leftover temp files after atomic commit.
        try (var files = Files.list(dir)) {
            assertTrue(files.allMatch(p -> p.getFileName().toString().equals("mock.json")),
                "only the committed file may remain");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void uncommittedOrTornWriteIsNeverTreatedAsCommitted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mock.json");
        ContractStore store = newStore(file);
        store.commit(om.convertValue(config(7, "kept"), MockConfig.class));

        // An interrupted update leaves only a stray temp file; the committed file
        // must stay intact.
        Path tmp = dir.resolve("mock-config88.tmp");
        Files.writeString(tmp, "{ \"version\": 88, \"contracts\": [ { broken json");

        ConfigRepository repository = new FileConfigRepository(file.toString());
        MockConfig loaded = repository.load();
        assertEquals(7L, loaded.version());

        // An invalid commit is rejected before persistence, so it cannot survive.
        ObjectNode invalid = config(8, "never");
        ((ObjectNode) invalid.get("contracts").get(0)).put("defaultScenario", "ghost");
        assertThrows(RuntimeException.class,
                () -> store.commit(om.convertValue(invalid, MockConfig.class)));

        ContractStore restarted = newStore(file);
        assertEquals(7L, restarted.current().version());
    }

    @Test
    void startsEmptyWhenNothingWasCommitted(@TempDir Path dir) {
        Path file = dir.resolve("absent.json");
        assertNull(new FileConfigRepository(file.toString()).load());
        ContractStore store = newStore(file);
        assertEquals(0L, store.current().version());
        assertTrue(store.current().contracts().isEmpty());
    }

    @Test
    void validatorWiringDoesNotRegress() {
        // Sanity: matcher/validator constructors used by other tests stay usable.
        new RequestMatcher();
        new RequestValidator();
    }
}
