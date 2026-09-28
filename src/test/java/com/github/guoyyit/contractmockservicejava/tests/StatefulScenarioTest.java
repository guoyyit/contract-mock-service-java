package com.github.guoyyit.contractmockservicejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.config;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.endpoint;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.requestSpec;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.response;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.scenario;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.step;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "contractmock.store-path=target/test-store/stateful.json")
class StatefulScenarioTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void sequenceChangesPerCallAndFallbackAppliesAfterExhaustion() {
        rest.postForEntity(url("/__admin/config"), config(endpoint("pay", "POST", "/pay",
                requestSpec(List.of(), List.of(), null),
                Map.of("default", scenario("default",
                        List.of(
                                step("first", response(200, "{\"phase\":\"first\"}")),
                                step("second", response(202, "{\"phase\":\"second\"}"))),
                        response(200, "{\"phase\":\"fallback\"}"))),
                "default")), String.class);

        assertThat(rest.postForEntity(url("/pay"), null, String.class).getBody())
                .contains("first");
        assertThat(rest.postForEntity(url("/pay"), null, String.class).getStatusCode().value())
                .isEqualTo(202);
        ResponseEntity<String> third = rest.postForEntity(url("/pay"), null, String.class);
        ResponseEntity<String> fourth = rest.postForEntity(url("/pay"), null, String.class);
        assertThat(third.getBody()).contains("fallback");
        assertThat(fourth.getBody()).contains("fallback");
    }

    @Test
    void resetMakesBehaviourDeterministicAgain() throws Exception {
        rest.postForEntity(url("/__admin/config"), config(endpoint("pay2", "POST", "/pay2",
                requestSpec(List.of(), List.of(), null),
                Map.of("default", scenario("default",
                        List.of(step("only", response(200, "{\"phase\":\"once\"}"))),
                        response(200, "{\"phase\":\"fallback\"}"))),
                "default")), String.class);

        assertThat(rest.postForEntity(url("/pay2"), null, String.class).getBody())
                .contains("once");
        assertThat(rest.postForEntity(url("/pay2"), null, String.class).getBody())
                .contains("fallback");

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest reset = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(url("/__admin/scenarios/reset?endpoint=pay2")))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        client.send(reset, java.net.http.HttpResponse.BodyHandlers.discarding());

        assertThat(rest.postForEntity(url("/pay2"), null, String.class).getBody())
                .contains("once");
        assertThat(rest.postForEntity(url("/pay2"), null, String.class).getBody())
                .contains("fallback");
    }
}
