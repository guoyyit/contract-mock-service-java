package com.github.guoyyit.contractmockservicejava.model;

import java.util.List;

/**
 * An entire, versioned configuration set. Updates always replace the whole
 * set atomically, never partially.
 */
public record MockConfig(
        long version,
        String note,
        List<Contract> contracts
) {
}
