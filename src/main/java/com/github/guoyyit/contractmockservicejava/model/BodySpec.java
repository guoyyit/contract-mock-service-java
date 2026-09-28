package com.github.guoyyit.contractmockservicejava.model;

import java.util.List;

/** Structural constraint on the request body. */
public record BodySpec(
        boolean required,
        String contentType,
        List<FieldSpec> fields
) {
}
