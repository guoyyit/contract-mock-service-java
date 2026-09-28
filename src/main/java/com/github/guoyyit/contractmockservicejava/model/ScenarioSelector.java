package com.github.guoyyit.contractmockservicejava.model;

/** Discriminator selecting a scenario based on a header, query parameter or body field. */
public record ScenarioSelector(
        SelectorSource source,
        String field,
        String equalsValue
) {
}
