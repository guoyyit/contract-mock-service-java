package com.github.guoyyit.contractmockservicejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.config;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.endpoint;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.json;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.requestSpec;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.response;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.scenario;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.scenarioInjected;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.scenarioWithSelector;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.step;
import static com.github.guoyyit.contractmockservicejava.model.SelectorSource.BODY;
import static com.github.guoyyit.contractmockservicejava.model.SelectorSource.HEADER;
import static com.github.guoyyit.contractmockservicejava.model.SelectorSource.QUERY;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "contractmock.store-path=target/test-store/selection.json")
class ScenarioSelectionInjectionTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void selectsScenarioByHeaderQueryAndBodyField() {
        rest.postForEntity(url("/__admin/config"), config(endpoint("charge", "POST", "/charge",
                requestSpec(List.of(), List.of(), null),
                Map.of(
                        "default", scenario("default", List.of(),
                                response(200, "{\"route\":\"default\"}")),
                        "vip", scenarioWithSelector("vip", HEADER, "X-Tier", "gold",
                                List.of(), response(200, "{\"route\":\"vip-header\"}")),
                        "promo", scenarioWithSelector("promo", QUERY, "campaign", "summer",
                                List.of(), response(200, "{\"route\":\"promo-query\"}")),
                        "type", scenarioWithSelector("type", BODY, "kind", "refund",
                                List.of(), response(200, "{\"route\":\"body-refund\"}"))),
                "default")), String.class);

        HttpHeaders vipHeaders = new HttpHeaders();
        vipHeaders.set("X-Tier", "gold");
        assertThat(rest.postForEntity(url("/charge"),
                new HttpEntity<>("", vipHeaders), String.class).getBody())
                .contains("vip-header");

        assertThat(rest.postForEntity(url("/charge?campaign=summer"), "",
                String.class).getBody()).contains("promo-query");

        assertThat(rest.postForEntity(url("/charge"), json("{\"kind\":\"refund\"}"),
                String.class).getBody()).contains("body-refund");

        assertThat(rest.postForEntity(url("/charge"), json("{\"kind\":\"normal\"}"),
                String.class).getBody()).contains("default");
    }

    @Test
    void injectedErrorOnlyAffectsTheConfiguredTargetScenario() {
        rest.postForEntity(url("/__admin/config"), config(
                endpoint("shared", "GET", "/shared",
                        requestSpec(List.of(), List.of(), null),
                        Map.of(
                                "default", scenario("default", List.of(),
                                        response(200, "{\"who\":\"default\"}")),
                                "broken", scenarioInjected("broken", null,
                                        response(500, "{\"error\":\"injected\"}"),
                                        List.of(), null)),
                        "default")), String.class);

        ResponseEntity<String> broken = rest.getForEntity(
                url("/shared?force=ignored"), String.class);
        // No selector on "broken": default is selected, so it must NOT receive the fault.
        assertThat(broken.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(broken.getBody()).contains("default");
    }

    @Test
    void injectedErrorReturnedWhenTargetScenarioSelected() {
        rest.postForEntity(url("/__admin/config"), config(
                endpoint("shared2", "GET", "/shared2",
                        requestSpec(List.of(), List.of(), null),
                        Map.of(
                                "default", scenario("default", List.of(),
                                        response(200, "{\"who\":\"default\"}")),
                                "broken", new com.github.guoyyit.contractmockservicejava.model.ScenarioDef(
                                        "broken",
                                        new com.github.guoyyit.contractmockservicejava.model.ScenarioSelector(
                                                QUERY, "mode", "fail"),
                                        List.of(),
                                        null,
                                        null,
                                        response(503, "{\"error\":\"injected-503\"}"))),
                        "default")), String.class);

        ResponseEntity<String> fault =
                rest.getForEntity(url("/shared2?mode=fail"), String.class);
        assertThat(fault.getStatusCode().value()).isEqualTo(503);
        assertThat(fault.getBody()).contains("injected-503");

        // A request not selecting the fault scenario is unaffected.
        ResponseEntity<String> healthy = rest.getForEntity(url("/shared2"), String.class);
        assertThat(healthy.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(healthy.getBody()).contains("default");
    }

    @Test
    void delayOnlyDelaysTheTargetRequest() {
        rest.postForEntity(url("/__admin/config"), config(
                endpoint("slow", "GET", "/slow",
                        requestSpec(List.of(), List.of(), null),
                        Map.of(
                                "default", scenario("default", List.of(),
                                        response(200, "{\"who\":\"fast\"}")),
                                "slow-scene", new com.github.guoyyit.contractmockservicejava.model.ScenarioDef(
                                        "slow-scene",
                                        new com.github.guoyyit.contractmockservicejava.model.ScenarioSelector(
                                                QUERY, "mode", "slow"),
                                        List.of(),
                                        response(200, "{\"who\":\"slow\"}"),
                                        400L,
                                        null)),
                        "default")), String.class);

        long start = System.currentTimeMillis();
        ResponseEntity<String> slow = rest.getForEntity(url("/slow?mode=slow"), String.class);
        long slowElapsed = System.currentTimeMillis() - start;

        start = System.currentTimeMillis();
        ResponseEntity<String> fast = rest.getForEntity(url("/slow"), String.class);
        long fastElapsed = System.currentTimeMillis() - start;

        assertThat(slow.getBody()).contains("slow");
        assertThat(fast.getBody()).contains("fast");
        assertThat(slowElapsed).isGreaterThanOrEqualTo(350L);
        assertThat(fastElapsed).isLessThan(300L);
    }
}
