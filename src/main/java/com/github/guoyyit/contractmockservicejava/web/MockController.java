package com.github.guoyyit.contractmockservicejava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.contractmockservicejava.service.MockDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Universal mock entry point.
 *
 * <p>The raw body is read permissively (any content type) and parsed as JSON by the
 * service layer, so contract validation — not the message converter — decides whether a
 * body is acceptable. A blank body is treated as "no body".
 */
@RestController
public class MockController {

    private final MockDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public MockController(MockDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(value = "/**", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.HEAD,
            RequestMethod.OPTIONS
    })
    public DeferredResult<ResponseEntity<JsonNode>> handle(
            HttpServletRequest request,
            @RequestBody(required = false) String rawBody) {
        JsonNode body = parseBody(rawBody);
        return dispatcher.dispatch(request, collectHeaders(request), collectQuery(request), body);
    }

    private JsonNode parseBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new com.github.guoyyit.contractmockservicejava.service.ServiceException(
                    "MALFORMED_REQUEST_BODY",
                    "Request body is not valid JSON: " + e.getMessage(),
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
    }

    private Map<String, String> collectHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private Map<String, String> collectQuery(HttpServletRequest request) {
        Map<String, String> query = new HashMap<>();
        Enumeration<String> names = request.getParameterNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            query.put(name, request.getParameter(name));
        }
        return query;
    }
}
