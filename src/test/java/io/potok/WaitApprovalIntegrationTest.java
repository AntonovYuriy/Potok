package io.potok;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Durable waits + approvals end to end: the wait parks and wakes; the
 * approval sends one Telegram message with two one-time links, the click
 * branches the DAG, the timeout is a result (never the DLQ), and a second
 * click changes nothing.
 */
class WaitApprovalIntegrationTest extends IntegrationTestBase {

    @org.springframework.test.context.DynamicPropertySource
    static void approvalProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        // the minimal-approval test relies on the chat default (backward-compat rule)
        registry.add("potok.telegram.default-chat-id", () -> "777");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOKEN = Pattern.compile("/hooks/approval/([0-9a-f]{64})");

    private String createAndRun(String yaml) {
        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", yaml);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = String.valueOf(created.getBody().get("id"));
        ResponseEntity<Map<String, Object>> run = postJson("/api/workflows/" + id + "/run", Map.of());
        assertThat(run.getStatusCode().is2xxSuccessful()).isTrue();
        return String.valueOf(run.getBody().get("executionId"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stepByName(Map<String, Object> execution, String name) {
        return ((List<Map<String, Object>>) execution.get("steps")).stream()
                .filter(s -> name.equals(s.get("name"))).findFirst()
                .orElse(Map.of("status", "(no row yet)")); // keeps await() polling instead of erroring
    }

    @Test
    void waitStepParksThenWakesAndAdvances() {
        WIRE_MOCK.stubFor(get("/before").willReturn(okJson("{}")));
        WIRE_MOCK.stubFor(get("/after").willReturn(okJson("{}")));
        String executionId = createAndRun("""
                name: wait-park-wake
                trigger:
                  webhook: { path: "wait-park-wake" }
                steps:
                  - name: before
                    action: http
                    with: { url: "%s/before" }
                  - name: pause
                    wait: 2s
                  - name: after
                    action: http
                    with: { url: "%s/after" }
                """.formatted(WIRE_MOCK.baseUrl(), WIRE_MOCK.baseUrl()));

        // mid-flight: the pause is WAITING and the execution shows it
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> execution = getExecution(executionId);
            assertThat(stepByName(execution, "pause").get("status")).isEqualTo("WAITING");
            assertThat(execution.get("status")).isEqualTo("WAITING");
            assertThat(String.valueOf(stepByName(execution, "pause").get("output")))
                    .contains("sleeping_until");
        });

        // wakes on its own and the DAG continues
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> execution = getExecution(executionId);
            assertThat(execution.get("status")).isEqualTo("SUCCEEDED");
            assertThat(stepByName(execution, "pause").get("status")).isEqualTo("SUCCEEDED");
        });
        WIRE_MOCK.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathMatching("/after")));
    }

    private String approvalYaml(String name, String timeout) {
        return """
                name: %s
                trigger:
                  webhook: { path: "%s" }
                steps:
                  - name: ask
                    action: approval
                    with:
                      chat_id: "123"
                      text: "Deploy v2.3?"
                      timeout: %s
                  - name: act
                    if: "{{ steps.ask.approved == true }}"
                    action: http
                    with:
                      method: POST
                      url: "%s/deploy"
                      body: "go"
                  - name: cancelled
                    needs: [ask]
                    if: "{{ steps.ask.approved == false }}"
                    action: http
                    with:
                      method: POST
                      url: "%s/cancelled"
                      body: "no"
                """.formatted(name, name, timeout, WIRE_MOCK.baseUrl(), WIRE_MOCK.baseUrl());
    }

    /** Both one-time links from the single Telegram message the stub captured for this chat text. */
    private record Links(String approve, String deny) {
    }

    private Links linksFromTelegram(String questionMarker) {
        var requests = WIRE_MOCK.findAll(postRequestedFor(urlPathMatching("/bot.*/sendMessage")));
        for (var request : requests) {
            String body = request.getBodyAsString();
            if (!body.contains(questionMarker)) {
                continue;
            }
            Matcher matcher = TOKEN.matcher(body);
            assertThat(matcher.find()).as("approve link present").isTrue();
            String first = matcher.group(1);
            assertThat(matcher.find()).as("deny link present").isTrue();
            return new Links(first, matcher.group(1));
        }
        throw new AssertionError("no telegram message containing " + questionMarker);
    }

    @Test
    void approvalApproveRunsTheYesBranch() {
        WIRE_MOCK.stubFor(post("/deploy").willReturn(okJson("{}")));
        WIRE_MOCK.stubFor(post("/cancelled").willReturn(okJson("{}")));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage")).willReturn(okJson("{\"ok\":true}")));

        String executionId = createAndRun(approvalYaml("approve-flow", "1h"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> execution = getExecution(executionId);
            assertThat(stepByName(execution, "ask").get("status")).isEqualTo("WAITING");
            assertThat(execution.get("status")).isEqualTo("WAITING");
        });
        Links links = linksFromTelegram("Deploy v2.3?");
        assertThat(links.approve()).isNotEqualTo(links.deny());

        ResponseEntity<String> page = rest.getForEntity("/hooks/approval/" + links.approve(), String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("Approved");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> execution = getExecution(executionId);
            assertThat(execution.get("status")).isEqualTo("SUCCEEDED");
            assertThat(stepByName(execution, "ask").get("status")).isEqualTo("SUCCEEDED");
            assertThat(String.valueOf(stepByName(execution, "ask").get("output")))
                    .contains("approved=true").contains("timed_out=false");
            assertThat(stepByName(execution, "act").get("status")).isEqualTo("SUCCEEDED");
            assertThat(stepByName(execution, "cancelled").get("status")).isEqualTo("SKIPPED");
        });
        WIRE_MOCK.verify(1, postRequestedFor(urlPathMatching("/deploy")));
        WIRE_MOCK.verify(0, postRequestedFor(urlPathMatching("/cancelled")));

        // one-time use: the second click is a friendly no-op
        ResponseEntity<String> again = rest.getForEntity("/hooks/approval/" + links.approve(), String.class);
        assertThat(again.getBody()).contains("Already decided");
        WIRE_MOCK.verify(1, postRequestedFor(urlPathMatching("/deploy")));
    }

    @Test
    void approvalDenyRunsTheNoBranch() {
        WIRE_MOCK.stubFor(post("/deploy").willReturn(okJson("{}")));
        WIRE_MOCK.stubFor(post("/cancelled").willReturn(okJson("{}")));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage")).willReturn(okJson("{\"ok\":true}")));

        String executionId = createAndRun(approvalYaml("deny-flow", "1h"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(stepByName(getExecution(executionId), "ask").get("status")).isEqualTo("WAITING"));

        Links links = linksFromTelegram("Deploy v2.3?");
        ResponseEntity<String> page = rest.getForEntity("/hooks/approval/" + links.deny(), String.class);
        assertThat(page.getBody()).contains("Denied");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> execution = getExecution(executionId);
            assertThat(execution.get("status")).isEqualTo("SUCCEEDED");
            assertThat(stepByName(execution, "act").get("status")).isEqualTo("SKIPPED");
            assertThat(stepByName(execution, "cancelled").get("status")).isEqualTo("SUCCEEDED");
        });
        WIRE_MOCK.verify(0, postRequestedFor(urlPathMatching("/deploy")));
    }

    @Test
    void approvalTimeoutIsAResultNotAFailure() {
        WIRE_MOCK.stubFor(post("/deploy").willReturn(okJson("{}")));
        WIRE_MOCK.stubFor(post("/cancelled").willReturn(okJson("{}")));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage")).willReturn(okJson("{\"ok\":true}")));

        String executionId = createAndRun(approvalYaml("timeout-flow", "2s"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(stepByName(getExecution(executionId), "ask").get("status")).isEqualTo("WAITING"));
        Links links = linksFromTelegram("Deploy v2.3?");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> execution = getExecution(executionId);
            assertThat(execution.get("status")).isEqualTo("SUCCEEDED"); // not FAILED — timeout is a result
            assertThat(String.valueOf(stepByName(execution, "ask").get("output")))
                    .contains("timed_out=true");
            assertThat(stepByName(execution, "cancelled").get("status")).isEqualTo("SUCCEEDED");
        });
        // nothing dead-lettered by the timeout
        Map<String, Object> dlq = rest.getForObject("/api/dlq?size=100", Map.class);
        assertThat(String.valueOf(dlq)).doesNotContain("timeout-flow").doesNotContain("ask");

        // a click after the timeout changes nothing
        ResponseEntity<String> late = rest.getForEntity("/hooks/approval/" + links.approve(), String.class);
        assertThat(late.getBody()).containsAnyOf("Already decided", "Expired");
        WIRE_MOCK.verify(0, postRequestedFor(urlPathMatching("/deploy")));
    }

    @Test
    void dashboardDecideEndpointApproves() {
        WIRE_MOCK.stubFor(post("/deploy").willReturn(okJson("{}")));
        WIRE_MOCK.stubFor(post("/cancelled").willReturn(okJson("{}")));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage")).willReturn(okJson("{\"ok\":true}")));

        String executionId = createAndRun(approvalYaml("dashboard-flow", "1h"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(stepByName(getExecution(executionId), "ask").get("status")).isEqualTo("WAITING"));

        ResponseEntity<Map<String, Object>> decide = postJson(
                "/api/executions/" + executionId + "/steps/ask/decide", Map.of("approved", true));
        assertThat(decide.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(decide.getBody().get("status")).isEqualTo("DECIDED");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathMatching("/deploy")));
    }

    /** Backward-compat: minimal approval (text only) gets telegram + 24h defaults. */
    @Test
    void minimalApprovalUsesDefaults() {
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage")).willReturn(okJson("{\"ok\":true}")));
        String executionId = createAndRun("""
                name: minimal-approval
                trigger:
                  webhook: { path: "minimal-approval" }
                steps:
                  - name: ask
                    action: approval
                    with: { text: "Minimal question?" }
                """);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(stepByName(getExecution(executionId), "ask").get("status")).isEqualTo("WAITING"));
        var requests = WIRE_MOCK.findAll(postRequestedFor(urlPathMatching("/bot.*/sendMessage")));
        String body = requests.stream().map(r -> r.getBodyAsString())
                .filter(b -> b.contains("Minimal question?")).findFirst().orElseThrow();
        assertThat(body).contains("Expires in 1d"); // the 24h default, shown human-style
        // default chat comes from potok.telegram.default-chat-id — present means the send happened
        Links links = linksFromTelegram("Minimal question?");
        rest.getForEntity("/hooks/approval/" + links.deny(), String.class);
    }

    @Test
    void unknownTokenIs404() {
        ResponseEntity<String> response = rest.getForEntity(
                "/hooks/approval/" + "0".repeat(64), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("invalid");
    }

    @Test
    void previewSimulatesWaitAndApprovalWithoutSideEffects() throws Exception {
        String tpl = java.nio.file.Files.readString(
                java.nio.file.Path.of("templates", "confirm-before-act.yaml.tpl"));
        String rendered = io.potok.template.TemplateRenderer.render(tpl, Map.of(
                "name", "pv-confirm", "path", "pv-confirm", "question", "Deploy?",
                "action_url", "https://example.com/hook", "timeout", "6h"));
        ResponseEntity<Map<String, Object>> preview = postYaml("/api/preview", rendered);
        assertThat(preview.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) preview.getBody().get("steps");
        Map<String, Object> ask = steps.stream()
                .filter(s -> "ask".equals(s.get("name"))).findFirst().orElseThrow();
        assertThat(ask.get("mode")).isEqualTo("simulated");
        assertThat((String) ask.get("human_summary")).contains("wait up to 6h");
        WIRE_MOCK.verify(0, postRequestedFor(urlPathMatching("/bot.*/sendMessage")));
    }
}
