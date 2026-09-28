package com.github.guoyyit.contractmockservicejava.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Normalized view of an incoming HTTP request used for matching.
 */
public record MockRequest(
        String method,
        String path,
        Map<String, List<String>> queryParams,
        Map<String, String> headers,
        JsonNode body
) {
}
