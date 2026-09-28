package com.github.guoyyit.contractmockservicejava.model;

/**
 * Stable, distinguishable error codes returned by the mock service.
 */
public enum ErrorCode {
    NO_MATCH("no_match", 404),
    METHOD_NOT_ALLOWED("method_not_allowed", 405),
    INVALID_QUERY("invalid_query", 400),
    INVALID_HEADER("invalid_header", 400),
    INVALID_BODY("invalid_body", 400),
    INVALID_SCENARIO_SELECTOR("invalid_scenario_selector", 400),
    REQUEST_TIMEOUT("request_timeout", 504),
    REQUEST_CANCELLED("request_cancelled", 499),
    SCENARIO_EXHAUSTED("scenario_exhausted", 500),
    INVALID_CONTRACT("invalid_contract", 400),
    CONFLICT("conflict", 409),
    INTERNAL_ERROR("internal_error", 500);

    private final String code;
    private final int httpStatus;

    ErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
