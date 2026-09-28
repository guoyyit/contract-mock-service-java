package com.github.guoyyit.contractmockservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;
import com.github.guoyyit.contractmockservicejava.store.ContractStore;
import com.github.guoyyit.contractmockservicejava.store.Snapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract updates must take effect as whole, immutable versions. Readers
 * during concurrent commits must observe only complete configurations.
 */
@SpringBootTest
class ConcurrentUpdateTest {

    @Autowired
    private ContractStore store;
    private final ObjectMapper om = new ObjectMapper();

    private ObjectNode config(long version, int contractCount) {
        ObjectNode config = om.createObjectNode();
        config.put("version", version);
        ArrayNode contracts = config.putArray("contracts");
        for (int i = 0; i < contractCount; i++) {
            ObjectNode c = contracts.addObject();
            c.put("id", "c-" + version + "-" + i);
            c.put("method", "GET");
            c.put("pathTemplate", "/v" + version + "/" + i);
            c.putArray("queryParams");
            c.putArray("headers");
            c.putNull("body");
            c.putArray("selectors");
            c.put("defaultScenario", "s");
            ObjectNode scenario = c.putArray("scenarios").addObject();
            scenario.put("name", "s");
            scenario.put("repeatLast", false);
            ObjectNode step = scenario.putArray("steps").addObject();
            step.put("status", 200);
            step.put("body", "v" + version);
            step.put("delayMillis", 0);
            step.put("error", false);
            step.putNull("errorCode");
        }
        return config;
    }

    private MockConfig toConfig(ObjectNode node) {
        return om.convertValue(node, MockConfig.class);
    }

    @Test
    void readersAlwaysSeeOneWholeSnapshotUnderConcurrentCommits() throws Exception {
        store.commit(toConfig(config(1, 4)));
        int writers = 6;
        int readersPerVersion = 200;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger writerSeq = new AtomicInteger();
        ConcurrentHashMap<Long, AtomicInteger> observedContractsPerVersion = new ConcurrentHashMap<>();
        ConcurrentHashMap<Long, AtomicInteger> badSnapshots = new ConcurrentHashMap<>();

        for (int w = 0; w < writers; w++) {
            int base = (w + 1) * 10;
            pool.submit(() -> {
                awaitQuietly(start);
                long v = base + writerSeq.incrementAndGet();
                store.commit(toConfig(config(v, 4)));
            });
        }
        for (int r = 0; r < readersPerVersion; r++) {
            pool.submit(() -> {
                awaitQuietly(start);
                Snapshot snapshot = store.current();
                long version = snapshot.version();
                // The visible contract set must be exactly this version's set.
                boolean whole = snapshot.contracts().size() == 4
                        && snapshot.contracts().stream().allMatch(
                                c -> c.id().startsWith("c-" + version + "-"));
                observedContractsPerVersion.computeIfAbsent(version, k -> new AtomicInteger())
                        .incrementAndGet();
                if (!whole) {
                    badSnapshots.computeIfAbsent(version, k -> new AtomicInteger()).incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "tasks should finish");

        assertTrue(badSnapshots.isEmpty(),
                "no reader may observe a mixed/new-old snapshot: " + badSnapshots);
        // Every observed snapshot carried four fully matching contracts.
        observedContractsPerVersion.values().forEach(i -> assertTrue(i.get() > 0));
        assertTrue(store.current().version() > 1);
        assertEquals(4, store.current().contracts().size());
    }

    @Test
    void invalidConfigIsRejectedAndActiveSnapshotStaysWhole() {
        store.commit(toConfig(config(90, 2)));
        Snapshot before = store.current();

        ObjectNode bad = config(91, 1);
        // Break it: default scenario that does not exist.
        ((ObjectNode) bad.get("contracts").get(0)).put("defaultScenario", "missing");
        try {
            store.commit(toConfig(bad));
            throw new AssertionError("invalid config must be rejected");
        } catch (Exception expected) {
            // expected
        }
        // Active snapshot untouched and still internally consistent.
        Snapshot after = store.current();
        assertEquals(before.version(), after.version());
        assertEquals(2, after.contracts().size());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
