package com.github.guoyyit.contractmockservicejava.model;

/** Constraint on one JSON body field, addressed by a dot path (e.g. {@code order.id}). */
public record FieldSpec(
        String path,
        JsonType type,
        boolean required
) {
}
