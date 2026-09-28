package com.github.guoyyit.contractmockservicejava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.config;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.endpoint;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.requestSpec;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.response;
import static com.github.guoyyit.contractmockservicejava.tests.Fixtures.scenario;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence/restart coverage. To guarantee execution order across the "write" and the
 * "read after restart" phases while keeping independent web contexts, a dedicated store
 * file is used and phases are ordered with {@link Order}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "contractmock.store-path=target/test-store/restart.json")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersistenceRestartTest {

    private static final String STORE = "target/test-store/restart.json";

    @BeforeAll
    static void cleanStore() throws Exception {
        Files.deleteIfExists(Path.of(STORE));
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @Order(1)
    void committedConfigIsDurable() {
        rest.postForEntity(url("/__admin/config"),
                config(endpoint("persisted", "GET", "/persisted",
                        requestSpec(List.of(), List.of(), null),
                        Map.of("default", scenario("default", List.of(),
                                response(200, "{\"marker\":\"committed-v77\"}"))),
                        "default")), String.class);

        Path file = Path.of(STORE);
        assertThat(Files.exists(file)).isTrue();
        assertThat(Unchecked.readString(file)).contains("committed-v77");
    }

    @Test
    @Order(2)
    void freshContextRestoresLatestCommittedState() {
        // Simulate a process restart: a brand new context over the same store file.
        try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(
                com.github.guoyyit.contractmockservicejava.Application.class)
                .run("--contractmock.store-path=" + STORE,
                        "--server.port=0")) {

            Integer newPort = restarted.getEnvironment()
                    .getProperty("local.server.port", Integer.class);
            assertThat(newPort).isNotNull();

            TestRestTemplate client = new TestRestTemplate();
            ResponseEntity<String> config = client.getForEntity(
                    "http://localhost:" + newPort + "/__admin/config", String.class);
            assertThat(config.getBody()).contains("persisted");

            ResponseEntity<String> served = client.getForEntity(
                    "http://localhost:" + newPort + "/persisted", String.class);
            assertThat(served.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(served.getBody()).contains("committed-v77");

            String docs = client.getForObject(
                    "http://localhost:" + newPort + "/__docs", String.class);
            assertThat(docs).contains("persisted").contains("committed-v77");
        }
    }

    @Test
    @Order(3)
    void unfinishedWriteCannotBeRecovered() throws Exception {
        // A truncated/temp file is never the canonical store; the atomic move either leaves
        // the previous file or the new one. Verify a stray temp file is ignored on restart.
        Path dir = Path.of("target/test-store");
        Files.createDirectories(dir);
        Path stray = dir.resolve("mock-config-stray.tmp");
        Files.writeString(stray, "{ this is not a finished write");

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(
                com.github.guoyyit.contractmockservicejava.Application.class)
                .run("--contractmock.store-path=" + STORE,
                        "--server.port=0")) {
            Integer newPort = ctx.getEnvironment()
                    .getProperty("local.server.port", Integer.class);
            String body = new TestRestTemplate().getForObject(
                    "http://localhost:" + newPort + "/persisted", String.class);
            assertThat(body).contains("committed-v77");
        }
        Files.deleteIfExists(stray);
    }

    static class Unchecked {
        static String readString(Path path) {
            try {
                return Files.readString(path);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
