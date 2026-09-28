package com.github.guoyyit.contractmockservicejava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.contractmockservicejava.config.MockProperties;
import com.github.guoyyit.contractmockservicejava.model.ApiError;
import com.github.guoyyit.contractmockservicejava.model.MatchResult;
import com.github.guoyyit.contractmockservicejava.model.MockResponse;
import com.github.guoyyit.contractmockservicejava.model.ScenarioDef;
import com.github.guoyyit.contractmockservicejava.model.ScenarioStep;
import com.github.guoyyit.contractmockservicejava.model.ValidationIssue;
import com.github.guoyyit.contractmockservicejava.store.ActiveRevision;
import com.github.guoyyit.contractmockservicejava.store.ContractStore;
import com.github.guoyyit.contractmockservicejava.store.ScenarioResolver;
import com.github.guoyyit.contractmockservicejava.store.ScenarioRuntime;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * Orchestrates one mocked request:
 * capture-revision -> match -> validate -> select scenario -> consume state ->
 * inject delay/error -> respond.
 *
 * <p>The {@link ActiveRevision} is captured once up front, so the entire request is
 * evaluated against either the old or the new complete configuration. Scenario state is
 * claimed before the delay begins, therefore a request cancelled while delayed releases
 * its scheduled task but never rewinds or duplicates a sequence step.
 */
@Service
public class MockDispatcher {

    private final ContractStore store;
    private final RequestMatcher matcher;
    private final RequestValidator validator;
    private final ScenarioResolver scenarioResolver;
    private final DelayTaskRegistry delayRegistry;
    private final MockProperties properties;
    private final ObjectMapper objectMapper;

    public MockDispatcher(
            ContractStore store,
            RequestMatcher matcher,
            RequestValidator validator,
            ScenarioResolver scenarioResolver,
            DelayTaskRegistry delayRegistry,
            MockProperties properties,
            ObjectMapper objectMapper) {
        this.store = store;
        this.matcher = matcher;
        this.validator = validator;
        this.scenarioResolver = scenarioResolver;
        this.delayRegistry = delayRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public DeferredResult<ResponseEntity<JsonNode>> dispatch(
            HttpServletRequest request,
            Map<String, String> headers,
            Map<String, String> query,
            JsonNode body) {
        ActiveRevision revision = store.current();
        String method = request.getMethod();
        String path = resolvePath(request);

        MatchResult match = matcher.match(revision, method, path);
        if (match == null) {
            return immediate(errorResponse(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND",
                    "No mock contract matches " + method + " " + path, null));
        }
        if (match.methodNotAllowed()) {
            ResponseEntity<JsonNode> entity = ResponseEntity
                    .status(HttpStatus.METHOD_NOT_ALLOWED)
                    .header("Allow", match.allowedMethods())
                    .body(errorBody("METHOD_NOT_ALLOWED",
                            "Method " + method + " is not allowed for " + path
                                    + "; allowed: " + match.allowedMethods(), null));
            return immediate(entity);
        }

        var endpoint = match.endpoint();
        List<ValidationIssue> issues = validator.validate(endpoint, headers, query, body);
        if (!issues.isEmpty()) {
            return immediate(errorResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Request does not satisfy the contract for " + method + " "
                            + endpoint.pathTemplate(), issues));
        }

        ScenarioDef scenario = scenarioResolver.selectScenario(endpoint, headers, query, body);
        if (scenario == null) {
            return immediate(errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "NO_SCENARIO",
                    "No scenario configured for endpoint '" + endpoint.id() + "'", null));
        }
        ScenarioRuntime runtime =
                scenarioResolver.runtime(revision, endpoint.id(), scenario.name());

        // State is consumed exactly once, before any delay/fault injection.
        long index = runtime == null ? 0L : runtime.nextIndex();
        ResolvedAction action = resolveAction(scenario, index);
        if (action.response() == null) {
            return immediate(errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    "NO_RESPONSE_DEFINED",
                    "Scenario '" + scenario.name() + "' on endpoint '" + endpoint.id()
                            + "' defines no response for call #" + (index + 1), null));
        }

        long delayMs = action.delayMs() == null ? 0L : action.delayMs();
        // The timeout is an absolute bound on total wait: it can fire while the scenario
        // delay is still pending, at which point the scheduled task is released.
        long timeoutMs = Math.max(1L, properties.getRequestTimeoutMs());
        DeferredResult<ResponseEntity<JsonNode>> result = new DeferredResult<>(timeoutMs);
        ResponseEntity<JsonNode> response = toResponseEntity(action.response());

        if (delayMs <= 0L) {
            result.setResult(response);
            return result;
        }

        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        ScheduledFuture<?> future = delayRegistry.schedule(delayMs, () -> {
            delayRegistry.complete(holder[0]);
            result.setResult(response);
        });
        holder[0] = future;
        delayRegistry.register(future, f -> {
            // Cancellation hook intentionally holds no other state: the sequence index
            // was already committed and must not be rolled back.
        });

        result.onTimeout(() -> {
            delayRegistry.cancel(future);
            result.setErrorResult(new ServiceException("REQUEST_TIMEOUT",
                    "Request timed out after " + timeoutMs + " ms while waiting in scenario '"
                            + scenario.name() + "'",
                    HttpStatus.GATEWAY_TIMEOUT));
        });
        // Fires on every terminal path, including client disconnect during the delay.
        result.onCompletion(() -> delayRegistry.cancel(future));

        return result;
    }

    private ResolvedAction resolveAction(ScenarioDef scenario, long index) {
        List<ScenarioStep> steps = scenario.steps();
        if (steps != null && index < steps.size()) {
            ScenarioStep step = steps.get((int) index);
            MockResponse response = step.errorResponse() != null
                    ? step.errorResponse()
                    : step.response();
            Long delay = step.delayMs() != null ? step.delayMs() : scenario.delayMs();
            return new ResolvedAction(response, delay);
        }
        // Sequence exhausted: the configured fault injection still applies to the target
        // request only; other requests/endpoints are untouched.
        MockResponse response = scenario.errorResponse() != null
                ? scenario.errorResponse()
                : scenario.fallback();
        return new ResolvedAction(response, scenario.delayMs());
    }

    private ResponseEntity<JsonNode> toResponseEntity(MockResponse mock) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(mock.status());
        if (mock.headers() != null) {
            mock.headers().forEach(builder::header);
        }
        return builder.body(mock.body());
    }

    private DeferredResult<ResponseEntity<JsonNode>> immediate(ResponseEntity<JsonNode> entity) {
        DeferredResult<ResponseEntity<JsonNode>> result = new DeferredResult<>();
        result.setResult(entity);
        return result;
    }

    private ResponseEntity<JsonNode> errorResponse(
            HttpStatus status, String code, String message, List<ValidationIssue> issues) {
        return ResponseEntity.status(status).body(errorBody(code, message, issues));
    }

    private JsonNode errorBody(String code, String message, List<ValidationIssue> issues) {
        return objectMapper.valueToTree(new ApiError(code, message, issues));
    }

    private String resolvePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private record ResolvedAction(MockResponse response, Long delayMs) {
    }
}
