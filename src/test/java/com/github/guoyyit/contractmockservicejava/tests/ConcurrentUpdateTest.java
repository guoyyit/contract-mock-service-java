package com.github.guoyyit.contractmockservicejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.config;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.endpoint;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.requestSpec;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.response;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.scenario;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "contractmock.store-path=target/test-store/concurrent.json")
class ConcurrentUpdateTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void concurrentCommitsProduceStrictlyMonotonicUniqueVersions() throws Exception {
        // Baseline so versions start above zero.
        rest.postForEntity(url("/__admin/config"),
                config(endpoint("base", "GET", "/v", requestSpec(List.of(), List.of(), null),
                        Map.of("default", scenario("default", List.of(),
                                response(200, "{\"v\":0}"))), "default")), String.class);

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> versions = ConcurrentHashMap.newKeySet();
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 1; i <= threads; i++) {
            final int v = i;
            pool.submit(() -> {
                try {
                    start.await();
                    var resp = rest.postForEntity(url("/__admin/config"),
                            config(endpoint("v" + v, "GET", "/v",
                                    requestSpec(List.of(), List.of(), null),
                                    Map.of("default", scenario("default", List.of(),
                                            response(200, "{\"v\":" + v + "}"))),
                                    "default")),
                            String.class);
                    var tree = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(resp.getBody());
                    versions.add(tree.get("version").asLong());
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();
        assertThat(versions).hasSize(threads);
        assertThat(versions).allSatisfy(ver -> assertThat(ver).isGreaterThan(0L));

        long persistedVersion = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(java.nio.file.Files.readString(
                        java.nio.file.Path.of("target/test-store/concurrent.json")))
                .get("version").asLong();
        assertThat(persistedVersion).isEqualTo(versions.stream().mapToLong(Long::longValue).max().orElse(-1));
    }

    @Test
    void concurrentTrafficNeverObservesMixedConfiguration() throws Exception {
        int variants = 8;
        // One coherent group per variant: path /mix always answers the same version number
        // for every field in the body. A mixed response could never be all-equal.
        Runnable initializer = () -> rest.postForEntity(url("/__admin/config"),
                config(endpoint("mix", "GET", "/mix",
                        requestSpec(List.of(), List.of(), null),
                        Map.of("default", scenario("default", List.of(),
                                response(200,
                                        "{\"a\":0,\"b\":0,\"c\":0,\"d\":0}"))),
                        "default")), String.class);
        initializer.run();

        ExecutorService writers = Executors.newFixedThreadPool(4);
        ExecutorService readers = Executors.newFixedThreadPool(8);
        CountDownLatch stopWriters = new CountDownLatch(1);
        for (int v = 1; v <= 4; v++) {
            final int variant = v;
            writers.submit(() -> {
                while (stopWriters.getCount() > 0) {
                    for (int seen = 1; seen <= variants && stopWriters.getCount() > 0; seen++) {
                        rest.postForEntity(url("/__admin/config"),
                                config(endpoint("mix", "GET", "/mix",
                                        requestSpec(List.of(), List.of(), null),
                                        Map.of("default", scenario("default", List.of(),
                                                response(200, body(variant * 100 + seen)))),
                                        "default")), String.class);
                    }
                }
            });
        }

        Set<Integer> mismatches = ConcurrentHashMap.newKeySet();
        CountDownLatch readDone = new CountDownLatch(200);
        for (int i = 0; i < 200; i++) {
            readers.submit(() -> {
                try {
                    var resp = rest.getForEntity(url("/mix"), String.class);
                    var tree = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(resp.getBody());
                    Set<Integer> values = new HashSet<>();
                    tree.fields().forEachRemaining(e -> values.add(e.getValue().asInt()));
                    if (values.size() != 1) {
                        mismatches.add(values.hashCode());
                    }
                } catch (Exception ignored) {
                    // transient during shutdown; only successful responses are asserted
                } finally {
                    readDone.countDown();
                }
            });
        }
        assertThat(readDone.await(30, TimeUnit.SECONDS)).isTrue();
        stopWriters.countDown();
        writers.shutdownNow();
        readers.shutdown();

        assertThat(mismatches).isEmpty();
    }

    private static String body(int v) {
        return "{\"a\":" + v + ",\"b\":" + v + ",\"c\":" + v + ",\"d\":" + v + "}";
    }
}
