package com.github.guoyyit.contractmockservicejava.web;

import com.github.guoyyit.contractmockservicejava.docs.DocsService;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.store.ContractStore;
import com.github.guoyyit.contractmockservicejava.store.Snapshot;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Administrative API: commit whole config versions, reset scenarios,
 * and read generated documentation.
 */
@RestController
@RequestMapping("/__admin")
public class AdminController {

    private final ContractStore store;
    private final DocsService docsService;

    public AdminController(ContractStore store, DocsService docsService) {
        this.store = store;
        this.docsService = docsService;
    }

    /** Commits a complete configuration set as one atomic version. */
    @PostMapping("/configs")
    public Map<String, Object> commit(@RequestBody MockConfig config) {
        Snapshot snapshot = store.commit(config);
        return Map.of("version", snapshot.version());
    }

    @GetMapping("/configs/current")
    public MockConfig current() {
        return store.current().config();
    }

    @PostMapping("/scenarios/reset")
    public Map<String, Object> reset(@RequestParam(required = false) String scenario) {
        if (scenario == null || scenario.isBlank()) {
            store.resetScenarios();
        } else {
            store.resetScenario(scenario);
        }
        return Map.of("version", store.current().version());
    }

    @GetMapping(value = "/docs", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public String docs() {
        return docsService.markdown();
    }
}
