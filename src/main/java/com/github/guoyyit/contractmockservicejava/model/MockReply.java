package com.github.guoyyit.contractmockservicejava.model;

import java.util.Map;

/**
 * The response chosen for a matched request.
 */
public record MockReply(
        int status,
        Map<String, String> headers,
        Object body,
        boolean error,
        String errorCode,
        String matchedContractId,
        String matchedScenario,
        int stepIndex,
        long version
) {
}
