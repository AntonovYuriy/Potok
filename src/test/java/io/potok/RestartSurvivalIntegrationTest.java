package io.potok;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The durability claim, proven literally: app #1 parks a wait and an approval
 * and DIES; a freshly booted app #2 on the same database wakes the sleeper on
 * time and resumes the approval on click. The only shared state is Postgres.
 */
class RestartSurvivalIntegrationTest {

    static final WireMockServer STUB = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static String jdbcUrl;
    static final RestTemplate REST = new RestTemplate();

    @BeforeAll
    static void infra() throws Exception {
        STUB.start();
        IntegrationTestBase.POSTGRES.isRunning(); // triggers the shared container start
        String database = "potok_restart";
        try (var connection = java.sql.DriverManager.getConnection(
                IntegrationTestBase.POSTGRES.getJdbcUrl(),
                IntegrationTestBase.POSTGRES.getUsername(),
                IntegrationTestBase.POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }
        jdbcUrl = IntegrationTestBase.POSTGRES.getJdbcUrl()
                .replace("/" + IntegrationTestBase.POSTGRES.getDatabaseName(), "/" + database);
    }

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    private ConfigurableApplicationContext boot(int port) {
        // command-line args: highest property precedence, beats application.yml placeholders
        return new SpringApplicationBuilder(PotokApplication.class).run(
                "--server.port=" + port,
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + IntegrationTestBase.POSTGRES.getUsername(),
                "--spring.datasource.password=" + IntegrationTestBase.POSTGRES.getPassword(),
                "--potok.telegram.api-base=" + STUB.baseUrl(),
                "--potok.telegram.bot-token=test-token",
                "--potok.queue.poll-interval=PT0.1S",
                "--potok.cron.refresh-interval=PT1H",
                "--potok.allow-private-urls=true");
    }

    private static String send(String base, String path, Object body, boolean yaml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(yaml ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON);
        return REST.postForObject(base + path, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void waitAndApprovalSurviveAFullRestart() throws Exception {
        STUB.stubFor(get("/after").willReturn(okJson("{}")));
        STUB.stubFor(post("/deploy").willReturn(okJson("{}")));
        STUB.stubFor(post(urlPathMatching("/bot.*/sendMessage")).willReturn(okJson("{\"ok\":true}")));

        int port1 = freePort();
        ConfigurableApplicationContext app1 = boot(port1);
        String base1 = "http://localhost:" + port1;

        // park a 4s wait and a 1h approval, then kill the app
        String waitWfId = id(send(base1, "/api/workflows", """
                name: restart-wait
                trigger:
                  webhook: { path: "restart-wait" }
                steps:
                  - name: pause
                    wait: 4s
                  - name: after
                    action: http
                    with: { url: "%s/after" }
                """.formatted(STUB.baseUrl()), true));
        String waitExecution = executionId(send(base1, "/api/workflows/" + waitWfId + "/run", "{}", false));

        String approvalWfId = id(send(base1, "/api/workflows", """
                name: restart-approval
                trigger:
                  webhook: { path: "restart-approval" }
                steps:
                  - name: ask
                    action: approval
                    with: { text: "Survive restart?", chat_id: "1" }
                  - name: act
                    if: "{{ steps.ask.approved == true }}"
                    action: http
                    with: { method: POST, url: "%s/deploy" }
                """.formatted(STUB.baseUrl()), true));
        String approvalExecution = executionId(
                send(base1, "/api/workflows/" + approvalWfId + "/run", "{}", false));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(REST.getForObject(base1 + "/api/executions/" + waitExecution, Map.class)
                    .get("status")).isEqualTo("WAITING");
            assertThat(REST.getForObject(base1 + "/api/executions/" + approvalExecution, Map.class)
                    .get("status")).isEqualTo("WAITING");
        });
        String approveToken = approveTokenFromStub();
        app1.close(); // the "crash": no JVM state survives this

        int port2 = freePort();
        ConfigurableApplicationContext app2 = boot(port2);
        String base2 = "http://localhost:" + port2;
        try {
            // the sleeper wakes on schedule in the NEW process
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(REST.getForObject(base2 + "/api/executions/" + waitExecution, Map.class)
                            .get("status")).isEqualTo("SUCCEEDED"));
            STUB.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                    .getRequestedFor(urlPathMatching("/after")));

            // the approval link minted by app #1 still works against app #2
            String page = REST.getForObject(base2 + "/hooks/approval/" + approveToken, String.class);
            assertThat(page).contains("Approved");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(REST.getForObject(base2 + "/api/executions/" + approvalExecution, Map.class)
                            .get("status")).isEqualTo("SUCCEEDED"));
            STUB.verify(1, postRequestedFor(urlPathMatching("/deploy")));
        } finally {
            app2.close();
        }
    }

    private static String approveTokenFromStub() {
        Pattern token = Pattern.compile("/hooks/approval/([0-9a-f]{32})");
        for (var request : STUB.findAll(postRequestedFor(urlPathMatching("/bot.*/sendMessage")))) {
            String body = request.getBodyAsString();
            if (body.contains("Survive restart?")) {
                Matcher matcher = token.matcher(body);
                assertThat(matcher.find()).isTrue();
                return matcher.group(1);
            }
        }
        throw new AssertionError("approval message not captured");
    }

    @SuppressWarnings("unchecked")
    private static String id(String json) throws Exception {
        return String.valueOf(new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, Map.class).get("id"));
    }

    @SuppressWarnings("unchecked")
    private static String executionId(String json) throws Exception {
        return String.valueOf(new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, Map.class).get("executionId"));
    }

    private static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
