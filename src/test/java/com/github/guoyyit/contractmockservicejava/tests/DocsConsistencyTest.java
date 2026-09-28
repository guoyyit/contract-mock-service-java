package com.github.guoyyit.contractmockservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.contractmockservicejava.docs.DocsService;
import com.github.guoyyit.contractmockservicejava.match.ConfigValidator;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.store.ConfigRepository;
import com.github.guoyyit.contractmockservicejava.store.ContractStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documentation is derived from the active snapshot and must change with it.
 */
class DocsConsistencyTest {

    private final ObjectMapper om = new ObjectMapper();

    private ContractStore emptyStore() {
        return new ContractStore(new ConfigRepository() {
            @Override
            public MockConfig load() {
                return null;
            }

            @Override
            public void save(MockConfig config) {
            }
        }, new ConfigValidator());
    }

    private ObjectNode config(long version, String path) {
        ObjectNode config = om.createObjectNode();
        config.put("version", version);
        ArrayNode contracts = config.putArray("contracts");
        ObjectNode c = contracts.addObject();
        c.put("id", "c" + version);
        c.put("method", "POST");
        c.put("pathTemplate", path);
        ArrayNode query = c.putArray("queryParams");
        ObjectNode q = query.addObject();
        q.put("name", "id");
        q.put("required", true);
        q.put("pattern", "[0-9]+");
        q.putNull("equals");
        c.putArray("headers");
        ObjectNode body = c.putObject("body");
        body.put("required", true);
        ArrayNode fields = body.putArray("fields");
        ObjectNode f = fields.addObject();
        f.put("path", "name");
        f.put("type", "string");
        f.put("required", true);
        f.putNull("pattern");
        f.putNull("equals");
        c.putArray("selectors");
        c.put("defaultScenario", "ok");
        ObjectNode scenario = c.putArray("scenarios").addObject();
        scenario.put("name", "ok");
        scenario.put("repeatLast", false);
        ObjectNode step = scenario.putArray("steps").addObject();
        step.put("status", 201);
        step.put("body", "created-v" + version);
        step.put("delayMillis", 0);
        step.put("error", false);
        step.putNull("errorCode");
        return config;
    }

    @Test
    void docsReflectActiveSnapshotAndUpdateAfterCommit() {
        ContractStore store = emptyStore();
        DocsService docs = new DocsService(store);

        store.commit(om.convertValue(config(1, "/alpha"), MockConfig.class));
        String v1 = docs.markdown();
        assertTrue(v1.contains("Configuration version: `1`"));
        assertTrue(v1.contains("`POST /alpha`"));
        assertTrue(v1.contains("`id`"));
        assertTrue(v1.contains("[0-9]+"));
        assertTrue(v1.contains("`name`"));
        assertTrue(v1.contains("created-v1"));
        for (String code : new String[]{"no_match", "invalid_query", "invalid_header",
                "invalid_body", "scenario_exhausted", "request_timeout", "request_cancelled"}) {
            assertTrue(v1.contains("`" + code + "`"), "docs must list error code " + code);
        }

        // Commit a whole new version: docs update synchronously, old path disappears.
        store.commit(om.convertValue(config(2, "/beta"), MockConfig.class));
        String v2 = docs.markdown();
        assertTrue(v2.contains("Configuration version: `2`"));
        assertTrue(v2.contains("`POST /beta`"));
        assertTrue(v2.contains("created-v2"));
        assertFalse(v2.contains("/alpha"));
        assertFalse(v2.contains("created-v1"));
    }

    @Test
    void emptySnapshotStillDocumentsErrorCodes() {
        String md = new DocsService(emptyStore()).markdown();
        assertTrue(md.contains("## Error codes"));
        assertTrue(md.contains("`internal_error`"));
    }
}
