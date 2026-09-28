package com.github.guoyyit.contractmockservicejava.web;

import com.github.guoyyit.contractmockservicejava.model.ErrorCode;
import com.github.guoyyit.contractmockservicejava.model.ErrorResponse;
import com.github.guoyyit.contractmockservicejava.model.MockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into the stable error envelope.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MockException.class)
    public ResponseEntity<ErrorResponse> handleMock(MockException ex) {
        ErrorCode code = ex.errorCode();
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.warn("internal mock failure", ex);
        }
        return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        log.warn("unhandled request failure", ex);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code, ex.getMessage()));
    }
}
