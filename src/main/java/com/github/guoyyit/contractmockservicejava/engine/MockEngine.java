package com.github.guoyyit.contractmockservicejava.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.guoyyit.contractmockservicejava.match.RequestMatcher;
import com.github.guoyyit.contractmockservicejava.match.RequestValidator;
import com.github.guoyyit.contractmockservicejava.match.RouteMatch;
import com.github.guoyyit.contractmockservicejava.model.Contract;
import com.github.guoyyit.contractmockservicejava.model.ErrorCode;
import com.github.guoyyit.contractmockservicejava.model.MockException;
import com.github.guoyyit.contractmockservicejava.model.MockReply;
import com.github.guoyyit.contractmockservicejava.model.MockRequest;
import com.github.guoyyit.contractmockservicejava.model.ResponseStep;
import com.github.guoyyit.contractmockservicejava.model.Scenario;
import com.github.guoyyit.contractmockservicejava.model.ScenarioSelector;
import com.github.guoyyit.contractmockservicejava.store.ContractStore;
import com.github.guoyyit.contractmockservicejava.store.ScenarioState;
import com.github.guoyyit.contractmockservicejava.store.Snapshot;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Core dispatch. A single immutable {@link Snapshot} is captured up front,
 * so every decision for one request sees exactly one configuration version.
 *
 * <p>The stateful step is consumed <em>before</em> any delay is scheduled.
 * Cancelling during the wait releases the scheduled timer but never rewinds
 * or double-consumes the counter, so consumed state is neither lost nor
 * duplicated.
 */
@Component
public class MockEngine {

    private final ContractStore store;
    private final RequestMatcher matcher;
    private final RequestValidator validator;
    private final ScheduledExecutorService scheduler;

    public MockEngine(ContractStore store, RequestMatcher matcher,
                      RequestValidator validator, ScheduledExecutorService mockDelayScheduler) {
        this.store = store;
        this.matcher = matcher;
        this.validator = validator;
        this.scheduler = mockDelayScheduler;
    }

    public CompletableFuture<MockReply> dispatch(MockRequest request, CancellationToken token) {
        // Capture one immutable version for the entire handling of this request.
        Snapshot snapshot = store.current();
        try {
            RouteMatch route = route(snapshot, request);
            if (route == null) {
                if (matcher.pathExistsWithOtherMethod(snapshot, request)) {
                    throw new MockException(ErrorCode.METHOD_NOT_ALLOWED,
                            "method " + request.method() + " not allowed for path " + request.path());
                }
                throw new MockException(ErrorCode.NO_MATCH,
                        "no contract matches " + request.method() + " " + request.path());
            }
            validator.validate(route, request);

            String scenarioName = selectScenario(snapshot, route, request);
            Consumed consumed = consumeStep(snapshot, route.contract(), scenarioName);
            MockReply reply = toReply(snapshot, route, scenarioName, consumed);
            return applyDelay(consumed.step(), reply, token);
        } catch (MockException e) {
            return CompletableFuture.failedFuture(e);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                    new MockException(ErrorCode.INTERNAL_ERROR, e.getMessage()));
        }
    }

    RouteMatch route(Snapshot snapshot, MockRequest request) {
        return matcher.match(snapshot, request);
    }

    /**
     * Selects a scenario using header/query/body selectors in declaration
     * order; falls back to the contract default scenario.
     */
    String selectScenario(Snapshot snapshot, RouteMatch route, MockRequest request) {
        Contract contract = route.contract();
        List<ScenarioSelector> selectors = contract.selectors();
        if (selectors != null) {
            for (ScenarioSelector selector : selectors) {
                String actual = selectorValue(selector, request);
                if (actual != null && actual.equals(selector.equals())) {
                    return selector.scenario();
                }
            }
        }
        return contract.defaultScenario();
    }

    private String selectorValue(ScenarioSelector selector, MockRequest request) {
        return switch (selector.source()) {
            case HEADER -> headerValue(request.headers(), selector.name());
            case QUERY -> {
                List<String> values = request.queryParams().get(selector.name());
                yield values == null || values.isEmpty() ? null : values.get(0);
            }
            case BODY -> {
                JsonNode node = navigate(request.body(), selector.name());
                if (node == null || node.isNull()) {
                    yield null;
                }
                yield node.isTextual() ? node.asText() : node.toString();
            }
        };
    }

    private String headerValue(Map<String, String> headers, String name) {
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private JsonNode navigate(JsonNode root, String dottedPath) {
        if (root == null) {
            return null;
        }
        JsonNode current = root;
        for (String segment : dottedPath.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }

    private record Consumed(Scenario scenario, ResponseStep step, int stepIndex) {
    }

    /**
     * Atomically consumes the next step for one scenario. The increment
     * happens here, before the delay, so a later cancel cannot undo it.
     */
    Consumed consumeStep(Snapshot snapshot, Contract contract, String scenarioName) {
        Scenario scenario = findScenario(contract, scenarioName);
        ScenarioState state = store.state(snapshot, contract.id(), scenarioName);
        List<ResponseStep> steps = scenario.steps();
        int index = state.incrementAndGet();
        if (index < steps.size()) {
            return new Consumed(scenario, steps.get(index), index);
        }
        if (scenario.repeatLast()) {
            int clamped = steps.size() - 1;
            return new Consumed(scenario, steps.get(clamped), index);
        }
        throw new MockException(ErrorCode.SCENARIO_EXHAUSTED,
                "scenario '" + scenarioName + "' on contract " + contract.id()
                        + " has no more steps after " + steps.size() + " call(s); reset to continue");
    }

    private Scenario findScenario(Contract contract, String name) {
        for (Scenario scenario : contract.scenarios()) {
            if (scenario.name().equals(name)) {
                return scenario;
            }
        }
        throw new MockException(ErrorCode.INVALID_SCENARIO_SELECTOR,
                "unknown scenario '" + name + "' for contract " + contract.id());
    }

    private MockReply toReply(Snapshot snapshot, RouteMatch route, String scenarioName,
                              Consumed consumed) {
        ResponseStep step = consumed.step();
        Map<String, String> headers = step.headers() == null
                ? Map.of() : new LinkedHashMap<>(step.headers());
        return new MockReply(step.status(), headers, step.body(),
                step.error(), step.errorCode(),
                route.contract().id(), scenarioName, consumed.stepIndex(), snapshot.version());
    }

    /**
     * Schedules the step delay (if any). Registering the scheduled future
     * with the token lets a client cancel/timeout release the pending timer.
     * The step was already consumed, so cancellation cannot replay it.
     */
    CompletableFuture<MockReply> applyDelay(ResponseStep step, MockReply reply,
                                            CancellationToken token) {
        CompletableFuture<MockReply> future = new CompletableFuture<>();
        if (token.isCancelled()) {
            failWithToken(future, token);
            return future;
        }
        Runnable failNow = () -> {
            // Guard against both cancel and timeout racing the scheduler.
            if (!future.isDone()) {
                failWithToken(future, token);
            }
        };
        if (step.delayMillis() <= 0) {
            future.complete(reply);
            return future;
        }
        ScheduledFuture<?> scheduled = scheduler.schedule(() -> {
            if (token.isCancelled()) {
                failNow.run();
            } else {
                future.complete(reply);
            }
        }, step.delayMillis(), TimeUnit.MILLISECONDS);
        // Release the timer if the request is cancelled/timed out while waiting.
        token.onCancel(() -> {
            scheduled.cancel(false);
            failNow.run();
        });
        return future;
    }

    private void failWithToken(CompletableFuture<MockReply> future, CancellationToken token) {
        if (token.isTimedOut()) {
            future.completeExceptionally(new java.util.concurrent.TimeoutException("request timed out"));
        } else {
            future.completeExceptionally(new CancellationException("request cancelled"));
        }
    }
}
