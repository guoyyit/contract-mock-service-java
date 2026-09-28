package com.github.guoyyit.contractmockservicejava.model;

/** Single structural validation failure, stable and distinguishable across locations. */
public record ValidationIssue(
        String location,
        String field,
        String reason,
        String expected,
        String actual
) {
}
