package com.github.guoyyit.contractmockservicejava.model;

import java.util.List;

/** Structural constraints for an endpoint: headers, query parameters and body. */
public record RequestSpec(
        List<ParamSpec> headers,
        List<ParamSpec> queryParams,
        BodySpec body
) {
}
