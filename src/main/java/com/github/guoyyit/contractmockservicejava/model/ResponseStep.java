package com.github.guoyyit.contractmockservicejava.model;

import java.util.Map;

/**
 * One stateful step in a scenario. Each matched call consumes one step
 * before any delay is scheduled.
 */
public record ResponseStep(
        int status,
        Map<String, String> headers,
        Object body,
        long delayMillis,
        boolean error,
        String errorCode
) {
}
