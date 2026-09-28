package com.github.guoyyit.contractmockservicejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;

import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.bodyRequired;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.config;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.endpoint;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.field;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.param;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.requestSpec;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.response;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.scenario;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.step;
import static com.github.guoyyit.contractmockservicejava.model.JsonType.INTEGER;
import static com.github.guoyyit.contractmockservicejava.model.JsonType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "contractmock.store-path=target/test-store/docs.json")
class DocumentConsistencyTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode commitAndDocs() {
        rest.postForEntity(url("/__admin/config"), config(endpoint("orders", "POST", "/orders",
                requestSpec(
                        List.of(param("X-Tenant", true, STRING, "[a-z]+")),
                        List.of(param("verbose", false, null, null)),
                        bodyRequired(List.of(
                                field("id", STRING, true),
                                field("amount", INTEGER, false)))),
                Map.of("default", scenario("default",
                        List.of(step("created", response(201,
                                "{\"orderId\":\"o-1\",\"total\":100}"))),
                        response(200, "{\"orderId\":\"fb\",\"total\":0}"))),
                "default")), String.class);
        return mapper.valueToTree(
                rest.getForEntity(url("/__docs"), Object.class).getBody());
    }

    @Test
    void documentDescribesPathConstraintsResponsesAndErrorCodes() throws Exception {
        JsonNode docs = commitAndDocs();

        assertThat(docs.get("version").asLong()).isGreaterThan(0L);
        JsonNode ep = docs.get("endpoints").get(0);
        assertThat(ep.get("method").asText()).isEqualTo("POST");
        assertThat(ep.get("path").asText()).isEqualTo("/orders");
        assertThat(ep.get("defaultScenario").asText()).isEqualTo("default");

        // Request constraints mirrored.
        JsonNode headers = ep.get("request").get("headers");
        assertThat(headers.toString()).contains("X-Tenant", "[a-z]+");
        JsonNode body = ep.get("request").get("body");
        assertThat(body.get("required").asBoolean()).isTrue();
        assertThat(body.get("fields").toString()).contains("id", "amount", "integer");

        // Response structure inferred from the configured body.
        JsonNode stepResponse = ep.get("scenarios").get(0).get("steps").get(0).get("response");
        assertThat(stepResponse.get("status").asInt()).isEqualTo(201);
        assertThat(stepResponse.get("bodySchema").get("properties").get("orderId").get("type").asText())
                .isEqualTo("string");
        assertThat(stepResponse.get("bodySchema").get("properties").get("total").get("type").asText())
                .isEqualTo("integer");

        // Stable error codes documented.
        List<String> codes = new java.util.ArrayList<>();
        docs.get("errorCodes").forEach(c -> codes.add(c.get("code").asText()));
        assertThat(codes).contains("VALIDATION_FAILED", "ENDPOINT_NOT_FOUND",
                "METHOD_NOT_ALLOWED", "REQUEST_TIMEOUT");
    }

    @Test
    void documentationChangesSynchronouslyAfterContractUpdate() throws Exception {
        JsonNode first = commitAndDocs();
        assertThat(first.toString()).contains("/orders");

        rest.postForEntity(url("/__admin/config"), config(endpoint("payments", "GET", "/payments",
                requestSpec(List.of(), List.of(), null),
                Map.of("default", scenario("default", List.of(),
                        response(200, "{\"paid\":true}"))),
                "default")), String.class);

        JsonNode second = mapper.valueToTree(
                rest.getForEntity(url("/__docs"), Object.class).getBody());

        assertThat(second.get("version").asLong()).isGreaterThan(first.get("version").asLong());
        assertThat(second.toString()).contains("/payments", "paid");
        assertThat(second.toString()).doesNotContain("/orders");
    }

    @Test
    void documentedResponseStatusMatchesActuallyServedStatus() {
        JsonNode docs = commitAndDocs();
        int documentedStatus = docs.get("endpoints").get(0)
                .get("scenarios").get(0).get("steps").get(0)
                .get("response").get("status").asInt();

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Tenant", "acme");
        org.springframework.http.ResponseEntity<String> served = rest.postForEntity(
                url("/orders"),
                new org.springframework.http.HttpEntity<>("{\"id\":\"x\",\"amount\":5}", headers),
                String.class);
        assertThat(served.getStatusCode().value()).isEqualTo(documentedStatus);
        assertThat(served.getBody()).contains("o-1");
    }

}
