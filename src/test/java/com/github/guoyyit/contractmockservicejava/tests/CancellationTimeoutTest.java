package com.github.guoyyit.contractmockservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.contractmockservicejava.engine.CancellationToken;
import com.github.guoyyit.contractmockservicejava.engine.MockEngine;
import com.github.guoyyit.contractmockservicejava.match.ConfigValidator;
import com.github.guoyyit.contractmockservicejava.match.RequestMatcher;
import com.github.guoyyit.contractmockservicejava.match.RequestValidator;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.model.MockReply;
import com.github.guoyyit.contractmockservicejava.model.MockRequest;
import com.github.guoyyit.contractmockservicejava.store.ContractStore;
import com.github.guoyyit.contractmockservicejava.store.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers cancellation during a scenario delay and request timeout. The
 * scheduled delay must be released and the already-consumed step must
 * neither be replayed nor lost.
 */
class CancellationTimeoutTest {

    private final ObjectMapper om = new ObjectMapper();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "test-delay");
                t.setDaemon(true);
                return t;
            });

    private ContractStore storeWithDelayedConfig() {
        ObjectNode config = om.createObjectNode();
        config.put("version", 1);
        ArrayNode contracts = config.putArray("contracts");
        ObjectNode c = contracts.addObject();
        c.put("id", "slow");
        c.put("method", "GET");
        c.put("pathTemplate", "/slow");
        c.putArray("queryParams");
        c.putArray("headers");
        c.putNull("body");
        c.putArray("selectors");
        c.put("defaultScenario", "s");
        ObjectNode scenario = c.putArray("scenarios").addObject();
        scenario.put("name", "s");
        scenario.put("repeatLast", false);
        ArrayNode steps = scenario.putArray("steps");
        for (String body : new String[]{"step-1", "step-2", "step-3"}) {
            ObjectNode step = steps.addObject();
            step.put("status", 200);
            step.put("body", body);
            step.put("delayMillis", 800);
            step.put("error", false);
            step.putNull("errorCode");
        }
        MockConfig parsed = om.convertValue(config, MockConfig.class);
        return new ContractStore(new InMemoryRepository(parsed), new ConfigValidator());
    }

    private MockEngine engine(ContractStore store) {
        return new MockEngine(store, new RequestMatcher(), new RequestValidator(), scheduler);
    }

    private MockRequest request() {
        return new MockRequest("GET", "/slow", Map.of(), Map.of(), null);
    }

    @Test
    void cancellingDuringDelayReleasesTimerAndDoesNotReplayConsumedStep() {
        ContractStore store = storeWithDelayedConfig();
        var engine = engine(store);

        CancellationToken firstToken = new CancellationToken();
        CompletableFuture<MockReply> first = engine.dispatch(request(), firstToken);

        // First call consumed step index 0 before sleeping.
        Snapshot snapshot = store.current();
        assertEquals(1, store.state(snapshot, "slow", "s").get());

        // Cancel while the 800ms delay is in flight.
        firstToken.cancel();
        assertThrows(CancellationException.class,
                () -> first.get(2, TimeUnit.SECONDS));

        // The consumed step must not be replayed: next call returns step 2.
        CancellationToken secondToken = new CancellationToken();
        MockReply second = engine.dispatch(request(), secondToken).join();
        assertEquals("step-2", second.body());
        assertEquals(1, second.stepIndex());
        assertEquals(2, store.state(store.current(), "slow", "s").get());
    }

    @Test
    void completingNormallyAfterDelayReturnsConfiguredReply() throws Exception {
        ContractStore store = storeWithDelayedConfig();
        var engine = engine(store);

        long start = System.currentTimeMillis();
        MockReply reply = engine.dispatch(request(), new CancellationToken())
                .get(2, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;
        assertEquals("step-1", reply.body());
        assertTrue(elapsed >= 700, "delay should be honored, elapsed=" + elapsed);
    }

    @Test
    void timingOutDuringDelayFailsWithTimeoutAndKeepsConsumedState() {
        ContractStore store = storeWithDelayedConfig();
        var engine = engine(store);

        CancellationToken token = new CancellationToken();
        CompletableFuture<MockReply> pending = engine.dispatch(request(), token);

        // Simulate the container timing the request out.
        token.timeout();

        // Timeout surfaces distinctly from client cancellation.
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> pending.get(2, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof TimeoutException,
                "expected timeout, got " + ex.getCause());
        // Consumed state is retained despite timeout: not lost, not duplicated.
        assertEquals(1, store.state(store.current(), "slow", "s").get());

        // Next request still starts from step 2.
        MockReply next = engine.dispatch(request(), new CancellationToken()).join();
        assertEquals("step-2", next.body());
        assertEquals(1, next.stepIndex());
    }

    /** In-memory repository avoiding file IO in this unit test. */
    private record InMemoryRepository(MockConfig config)
            implements com.github.guoyyit.contractmockservicejava.store.ConfigRepository {
        @Override
        public MockConfig load() {
            return config;
        }

        @Override
        public void save(MockConfig config) {
        }
    }
}
