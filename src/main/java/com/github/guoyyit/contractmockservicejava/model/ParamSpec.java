package com.github.guoyyit.contractmockservicejava.model;

/** Constraint on a single request header or query parameter. */
public record ParamSpec(
        String name,
        boolean required,
        JsonType type,
        String pattern
) {
}
