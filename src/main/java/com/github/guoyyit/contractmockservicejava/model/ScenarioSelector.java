package com.github.guoyyit.contractmockservicejava.model;

/**
 * Chooses a scenario based on a header / query / body-field value.
 *
 * @param source HEADER, QUERY or BODY
 * @param name   header / query name, or dotted body field path
 */
public record ScenarioSelector(
        SelectorSource source,
        String name,
        String equals,
        String scenario
) {
    public enum SelectorSource {
        HEADER, QUERY, BODY
    }
}
