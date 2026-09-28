package com.github.guoyyit.contractmockservicejava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.service.AdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrative endpoints: inspect/commit config and reset scenario state. */
@RestController
@RequestMapping("/__admin")
public class AdminController {

    private final AdminService adminService;
    private final ObjectMapper objectMapper;

    public AdminController(AdminService adminService, ObjectMapper objectMapper) {
        this.adminService = adminService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/config")
    public MockConfig getConfig() {
        return adminService.current().config();
    }

    /** Atomically validate, persist and activate a whole configuration group. */
    @PostMapping("/config")
    public MockConfig commit(@RequestBody MockConfig candidate) {
        return adminService.commit(candidate).config();
    }

    /**
     * Reset scenario call sequences. With no parameters every scenario is reset; with an
     * endpoint id all its scenarios are reset; with both, only that scenario is reset.
     */
    @PostMapping("/scenarios/reset")
    public ObjectNode reset(
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) String scenario) {
        adminService.resetScenarios(endpoint, scenario);
        ObjectNode ack = objectMapper.createObjectNode();
        ack.put("reset", true);
        if (endpoint != null) {
            ack.put("endpoint", endpoint);
        }
        if (scenario != null) {
            ack.put("scenario", scenario);
        }
        return ack;
    }
}
