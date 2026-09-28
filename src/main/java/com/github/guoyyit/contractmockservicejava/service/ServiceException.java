package com.github.guoyyit.contractmockservicejava.service;

import org.springframework.http.HttpStatus;

/** Service-level failure carrying a stable error code and an HTTP status. */
public class ServiceException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public ServiceException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
