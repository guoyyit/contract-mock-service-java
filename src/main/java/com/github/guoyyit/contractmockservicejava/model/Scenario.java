package com.github.guoyyit.contractmockservicejava.model;

import java.util.List;

/**
 * A named, stateful scenario. Calls consume steps in order; when the steps
 * are exhausted the {@code repeatLast} flag decides final behavior.
 */
public record Scenario(
        String name,
        List<ResponseStep> steps,
        boolean repeatLast
) {
}
