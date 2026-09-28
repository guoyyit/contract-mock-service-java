package com.github.guoyyit.contractmockservicejava.model;

import java.util.List;

/**
 * Structural constraints on a JSON request body.
 */
public record BodySpec(
        boolean required,
        List<FieldSpec> fields
) {
}
