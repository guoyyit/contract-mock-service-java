package com.github.guoyyit.contractmockservicejava.model;

/**
 * Constraint on a single query parameter or header.
 */
public record ParamSpec(
        String name,
        boolean required,
        String pattern,
        String equals
) {
    public ParamSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("param name must not be blank");
        }
    }
}
