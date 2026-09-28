package com.github.guoyyit.contractmockservicejava.model;

import java.util.List;

/** Whole configuration group that takes effect atomically as one version. */
public record MockConfig(
        Long version,
        String committedAt,
        List<EndpointContract> endpoints
) {
}
