package com.github.guoyyit.contractmockservicejava.model;

/**
 * Constraint on a single JSON body field, addressed by a dot path
 * (for example {@code order.id}).
 */
public record FieldSpec(
        String path,
        String type,
        boolean required,
        String pattern,
        String equals
) {
    public FieldSpec {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("field path must not be blank");
        }
    }
}
