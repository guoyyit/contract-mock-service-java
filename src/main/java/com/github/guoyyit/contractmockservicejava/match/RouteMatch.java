package com.github.guoyyit.contractmockservicejava.match;

import com.github.guoyyit.contractmockservicejava.model.Contract;

import java.util.Map;

/**
 * Result of matching method + path template.
 */
public record RouteMatch(Contract contract, Map<String, String> pathVariables) {

    public static RouteMatch of(Contract contract, Map<String, String> pathVariables) {
        return new RouteMatch(contract, pathVariables);
    }
}
