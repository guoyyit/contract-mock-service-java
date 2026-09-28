package com.github.guoyyit.contractmockservicejava.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** A response the mock can return: status, headers and a JSON body. */
public record MockResponse(
        int status,
        Map<String, String> headers,
        JsonNode body
) {
}
