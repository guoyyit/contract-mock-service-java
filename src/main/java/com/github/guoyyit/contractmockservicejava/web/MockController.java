package com.github.guoyyit.contractmockservicejava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.guoyyit.contractmockservicejava.engine.CancellationToken;
import com.github.guoyyit.contractmockservicejava.engine.MockEngine;
import com.github.guoyyit.contractmockservicejava.model.ErrorCode;
import com.github.guoyyit.contractmockservicejava.model.ErrorResponse;
import com.github.guoyyit.contractmockservicejava.model.MockException;
import com.github.guoyyit.contractmockservicejava.model.MockReply;
import com.github.guoyyit.contractmockservicejava.model.MockRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Catch-all entry point for mocked interface traffic. Uses async
 * {@link DeferredResult} so that cancelling a client request releases any
 * scheduled scenario delay.
 */
@RestController
public class MockController {

    private final MockEngine engine;
    private final long requestTimeoutMillis;

    public MockController(MockEngine engine,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${mock.request-timeout-ms:30000}") long requestTimeoutMillis) {
        this.engine = engine;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.HEAD, RequestMethod.OPTIONS},
            value = "/**")
    public DeferredResult<ResponseEntity<Object>> handle(HttpServletRequest servletRequest,
                                                         @RequestBody(required = false) JsonNode body) {
        MockRequest request = toMockRequest(servletRequest, body);
        DeferredResult<ResponseEntity<Object>> result =
                new DeferredResult<>(requestTimeoutMillis);
        CancellationToken token = new CancellationToken();
        result.onTimeout(() -> {
            token.timeout();
            result.setErrorResult(errorEntity(ErrorCode.REQUEST_TIMEOUT, "request timed out"));
        });
        result.onError(t -> token.cancel());
        result.onCompletion(token::cancel);

        engine.dispatch(request, token).whenComplete((reply, ex) -> {
            if (ex != null) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                result.setErrorResult(toErrorEntity(cause));
            } else {
                result.setResult(toEntity(reply));
            }
        });
        return result;
    }

    private MockRequest toMockRequest(HttpServletRequest servletRequest, JsonNode body) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = servletRequest.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, servletRequest.getHeader(name));
            }
        }
        Map<String, List<String>> query = new HashMap<>();
        servletRequest.getParameterMap().forEach((k, v) -> query.put(k, List.of(v)));
        return new MockRequest(servletRequest.getMethod(), servletRequest.getRequestURI(),
                query, headers, body);
    }

    static ResponseEntity<Object> toEntity(MockReply reply) {
        HttpHeaders httpHeaders = new HttpHeaders();
        if (reply.headers() != null) {
            reply.headers().forEach(httpHeaders::add);
        }
        Object payload = reply.body();
        if (payload != null && !httpHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        }
        return ResponseEntity.status(reply.status()).headers(httpHeaders).body(payload);
    }

    static ResponseEntity<Object> toErrorEntity(Throwable cause) {
        if (cause instanceof MockException mockException) {
            ErrorCode code = mockException.errorCode();
            return ResponseEntity.status(code.httpStatus())
                    .body(ErrorResponse.of(code, mockException.getMessage()));
        }
        if (cause instanceof CancellationException) {
            return errorEntity(ErrorCode.REQUEST_CANCELLED, "request cancelled");
        }
        if (cause instanceof TimeoutException) {
            return errorEntity(ErrorCode.REQUEST_TIMEOUT, "request timed out");
        }
        return errorEntity(ErrorCode.INTERNAL_ERROR,
                cause.getMessage() == null ? "internal error" : cause.getMessage());
    }

    static ResponseEntity<Object> errorEntity(ErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code, message));
    }
}
