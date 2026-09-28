package com.github.guoyyit.contractmockservicejava.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.contractmockservicejava.service.AdminService;
import com.github.guoyyit.contractmockservicejava.service.DocumentGenerator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves documentation generated from the active contracts. */
@RestController
public class DocsController {

    private final AdminService adminService;
    private final DocumentGenerator documentGenerator;

    public DocsController(AdminService adminService, DocumentGenerator documentGenerator) {
        this.adminService = adminService;
        this.documentGenerator = documentGenerator;
    }

    @GetMapping("/__docs")
    public ObjectNode docs() {
        return documentGenerator.generate(adminService.current());
    }
}
