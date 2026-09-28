package com.github.guoyyit.contractmockservicejava.model;

import java.util.List;

/** Stable error envelope returned for every failure. */
public record ApiError(
        String code,
        String message,
        List<ValidationIssue> issues
) {
}
