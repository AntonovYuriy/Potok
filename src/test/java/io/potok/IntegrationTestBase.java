package io.potok;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

/**
 * Shared infra for integration tests: one Postgres container and one WireMock
 * server for the whole JVM. Retry backoff and poll interval are shrunk so
 * retry scenarios complete in milliseconds, not minutes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        POSTGRES.start();
        WIRE_MOCK.start();
    }

    private static final java.util.concurrent.atomic.AtomicInteger DB_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Cached Spring contexts keep their queue workers polling. Give every context its
        // own database so workers from a previous context never steal this context's jobs.
        String database = "potok_it_" + DB_COUNTER.incrementAndGet();
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("failed to create per-context database", e);
        }
        registry.add("spring.datasource.url",
                () -> POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("potok.telegram.api-base", WIRE_MOCK::baseUrl);
        registry.add("potok.telegram.bot-token", () -> "test-token");
        registry.add("potok.queue.poll-interval", () -> "PT0.1S");
        registry.add("potok.queue.retry-backoff", () -> "PT0.2S");
        registry.add("potok.cron.refresh-interval", () -> "PT1H");
    }

    @Autowired
    protected TestRestTemplate rest;

    @BeforeEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
    }

    protected ResponseEntity<Map<String, Object>> postYaml(String url, String yaml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return rest.exchange(url, org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(yaml, headers), MAP_TYPE);
    }

    protected ResponseEntity<Map<String, Object>> postJson(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url, org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> getExecution(String executionId) {
        return rest.getForObject("/api/executions/" + executionId, Map.class);
    }

    protected static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new org.springframework.core.ParameterizedTypeReference<>() {
            };
}
