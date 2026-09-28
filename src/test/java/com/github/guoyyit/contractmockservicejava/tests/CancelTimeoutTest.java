package com.github.guoyyit.contractmockservicejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.config;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.endpoint;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.requestSpec;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.response;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.scenarioInjected;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.step;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"contractmock.store-path=target/test-store/cancel.json",
                "contractmock.request-timeout-ms=250"})
class CancelTimeoutTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void cancellingDuringDelayConsumesExactlyOneStepAndReleasesTheRequest() throws Exception {
        // Two steps: first delayed (the one we cancel), second immediate.
        rest.postForEntity(url("/__admin/config"),
                config(endpoint("seq", "GET", "/seq",
                        requestSpec(List.of(), List.of(), null),
                        Map.of("default",
                                new com.github.guoyyit.contractmockservicejava.model.ScenarioDef(
                                        "default", null,
                                        List.of(
                                                step("late", response(200, "{\"p\":\"late\"}"),
                                                        800L, null),
                                                step("soon", response(200, "{\"p\":\"soon\"}"),
                                                        null, null)),
                                        response(200, "{\"p\":\"fallback\"}"),
                                        null, null)),
                        "default")), String.class);

        HttpRequest delayed = HttpRequest.newBuilder(URI.create(url("/seq")))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        long start = System.currentTimeMillis();
        CompletableFuture<HttpResponse<String>> future =
                client.sendAsync(delayed, HttpResponse.BodyHandlers.ofString());
        Thread.sleep(150); // ensure the request entered the delay
        future.cancel(true);

        boolean cancelled = false;
        try {
            future.get();
        } catch (Exception e) {
            cancelled = true;
        }
        assertThat(cancelled).isTrue();
        // The client must have been released promptly, well before the 800ms delay.
        assertThat(System.currentTimeMillis() - start).isLessThan(700L);

        // The consumed step is neither repeated nor lost: next call returns step 2, not 1.
        HttpResponse<String> next = client.send(
                HttpRequest.newBuilder(URI.create(url("/seq"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(next.body()).contains("soon");

        // After reset the sequence is deterministic again starting at the delayed step.
        client.send(HttpRequest.newBuilder(
                                URI.create(url("/__admin/scenarios/reset?endpoint=seq")))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void serverSideTimeoutReturns504WithoutAdvancingSequenceTwice() throws Exception {
        // request-timeout-ms=250 while the first step delays 1500ms, so it times out in the delay.
        rest.postForEntity(url("/__admin/config"),
                config(endpoint("slow", "GET", "/slow",
                        requestSpec(List.of(), List.of(), null),
                        Map.of("default",
                                new com.github.guoyyit.contractmockservicejava.model.ScenarioDef(
                                        "default", null,
                                        List.of(
                                                step("one", response(200, "{\"p\":\"one\"}"),
                                                        1500L, null),
                                                step("two", response(200, "{\"p\":\"two\"}"),
                                                        null, null)),
                                        response(200, "{\"p\":\"fallback\"}"),
                                        null, null)),
                        "default")), String.class);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/slow")))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        long start = System.currentTimeMillis();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(resp.statusCode()).isEqualTo(504);
        assertThat(resp.body()).contains("REQUEST_TIMEOUT");
        assertThat(elapsed).isLessThan(1400L);

        // The single consumed step must not be duplicated: the next call gets step 2.
        HttpResponse<String> next = client.send(
                HttpRequest.newBuilder(URI.create(url("/slow"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(next.body()).contains("two");
    }
}
