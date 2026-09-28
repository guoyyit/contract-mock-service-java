package com.github.guoyyit.contractmockservicejava.model;

import java.util.Map;

/** Result of matching an incoming request against the contracts of one revision. */
public record MatchResult(
        EndpointContract endpoint,
        Map<String, String> pathVariables,
        boolean methodNotAllowed,
        String allowedMethods
) {
}
