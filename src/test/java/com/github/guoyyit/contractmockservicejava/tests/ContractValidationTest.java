package com.github.guoyyit.contractmockservicejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.bodyRequired;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.config;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.endpoint;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.field;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.json;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.param;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.requestSpec;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.response;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.scenario;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.step;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.guoyyit.contractmockservicejava.model.JsonType;
import com.github.guoyyit.contractmockservicejava.model.MockConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "contractmock.store-path=target/test-store/validation.json")
class ContractValidationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void commit(MockConfig cfg) {
        rest.postForEntity(url("/__admin/config"), cfg, String.class);
    }

    @Test
    void matchesMethodPathTemplateAndReturnsContractResponse() {
        commit(config(endpoint("orders-get", "GET", "/orders/{id}",
                requestSpec(List.of(param("X-Tenant", true, JsonType.STRING, null)), List.of(), null),
                Map.of("default", scenario("default",
                        List.of(step("s1", response(200, "{\"id\":\"x\",\"state\":\"ok\"}"))),
                        response(200, "{\"state\":\"fallback\"}"))),
                "default")));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant", "acme");
        ResponseEntity<String> response = rest.exchange(
                url("/orders/42"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"state\"", "ok");
    }

    @Test
    void unknownPathReturnsDistinguishable404Code() {
        commit(config(endpoint("orders-get", "GET", "/orders/{id}",
                requestSpec(List.of(), List.of(), null),
                Map.of("default", scenario("default", List.of(),
                        response(200, "{\"state\":\"ok\"}"))),
                "default")));

        ResponseEntity<String> response = rest.getForEntity(url("/nope"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("ENDPOINT_NOT_FOUND");
    }

    @Test
    void wrongMethodReturns405WithAllowHeader() {
        commit(config(endpoint("orders-post", "POST", "/orders",
                requestSpec(List.of(), List.of(), null),
                Map.of("default", scenario("default", List.of(),
                        response(201, "{\"created\":true}"))),
                "default")));

        ResponseEntity<String> response = rest.getForEntity(url("/orders"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).contains("METHOD_NOT_ALLOWED");
        assertThat(response.getHeaders().getFirst("Allow")).contains("POST");
    }

    @Test
    void missingRequiredHeaderAndQueryAreReportedSeparately() {
        commit(config(endpoint("orders-list", "GET", "/orders",
                requestSpec(
                        List.of(param("X-Tenant", true, JsonType.STRING, null)),
                        List.of(param("page", true, JsonType.INTEGER, null)),
                        null),
                Map.of("default", scenario("default", List.of(),
                        response(200, "{\"ok\":true}"))),
                "default")));

        ResponseEntity<String> response = rest.getForEntity(url("/orders"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("VALIDATION_FAILED");
        assertThat(response.getBody()).contains("\"location\":\"header\"", "X-Tenant");
        assertThat(response.getBody()).contains("\"location\":\"query\"", "page");
    }

    @Test
    void wrongTypesAreReportedWithExpectedAndActual() {
        commit(config(endpoint("orders-list", "GET", "/orders",
                requestSpec(
                        List.of(),
                        List.of(param("page", false, JsonType.INTEGER, null)),
                        null),
                Map.of("default", scenario("default", List.of(),
                        response(200, "{\"ok\":true}"))),
                "default")));

        ResponseEntity<String> response =
                rest.getForEntity(url("/orders?page=abc"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody())
                .contains("type_mismatch")
                .contains("\"expected\":\"integer\"");
    }

    @Test
    void bodyStructuralValidationReportsMissingAndTypeMismatchFields() {
        commit(config(endpoint("orders-create", "POST", "/orders",
                requestSpec(List.of(), List.of(),
                        bodyRequired(List.of(
                                field("customerId", JsonType.STRING, true),
                                field("amount", JsonType.NUMBER, false)))),
                Map.of("default", scenario("default", List.of(),
                        response(201, "{\"created\":true}"))),
                "default")));

        ResponseEntity<String> response = rest.postForEntity(url("/orders"),
                json("{\"amount\":\"not-a-number\"}"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody())
                .contains("VALIDATION_FAILED")
                .contains("\"location\":\"body\"")
                .contains("customerId")
                .contains("amount")
                .contains("type_mismatch");
    }

    @Test
    void malformedJsonReturnsStableMalformedCode() {
        commit(config(endpoint("orders-create", "POST", "/orders",
                requestSpec(List.of(), List.of(),
                        bodyRequired(List.of(field("customerId", JsonType.STRING, true)))),
                Map.of("default", scenario("default", List.of(),
                        response(201, "{\"created\":true}"))),
                "default")));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        ResponseEntity<String> response = rest.exchange(url("/orders"), HttpMethod.POST,
                new HttpEntity<>("{not-json", headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("MALFORMED_REQUEST_BODY");
    }
}
