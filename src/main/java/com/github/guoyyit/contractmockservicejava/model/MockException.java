package com.github.guoyyit.contractmockservicejava.model;

/**
 * Business failure carrying a stable {@link ErrorCode}.
 */
public class MockException extends RuntimeException {

    private final ErrorCode errorCode;

    public MockException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
