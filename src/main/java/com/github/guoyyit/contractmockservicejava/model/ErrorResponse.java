package com.github.guoyyit.contractmockservicejava.model;

/**
 * Stable JSON error envelope: {@code {"error":{"code","message"}}}.
 */
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(new ErrorBody(code.code(), message));
    }
}
