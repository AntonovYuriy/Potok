package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Native Telegram buttons: the approval message carries an inline keyboard
 * with callback_data, the updates poller picks the tap up, the decision lands
 * without any browser, the user gets a toast, and the message is edited so
 * the buttons disappear.
 */
class TelegramButtonsIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void buttonsProperties(DynamicPropertyRegistry registry) {
        registry.add("potok.telegram.poll-updates", () -> "true");
        registry.add("potok.telegram.updates-idle", () -> "PT0.1S");
    }

    private static final Pattern CALLBACK = Pattern.compile("\"callback_data\":\"(apr|dny):([0-9a-f]{32})\"");

    @Test
    void buttonTapApprovesWithoutABrowser() {
        WIRE_MOCK.stubFor(post("/deploy").willReturn(okJson("{}")));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage"))
                .willReturn(okJson("{\"ok\":true,\"result\":{\"message_id\":42}}")));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/answerCallbackQuery")).willReturn(okJson("{\"ok\":true}")));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/editMessageText")).willReturn(okJson("{\"ok\":true}")));
        // the poller idles on an empty update list until we inject the tap
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/getUpdates"))
                .willReturn(okJson("{\"ok\":true,\"result\":[]}")));

        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", """
                name: buttons-flow
                trigger:
                  webhook: { path: "buttons-flow" }
                steps:
                  - name: ask
                    action: approval
                    with: { text: "Tap to deploy?", chat_id: "123" }
                  - name: act
                    if: "{{ steps.ask.approved == true }}"
                    action: http
                    with: { method: POST, url: "%s/deploy" }
                """.formatted(WIRE_MOCK.baseUrl()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workflowId = String.valueOf(created.getBody().get("id"));
        ResponseEntity<Map<String, Object>> run = postJson("/api/workflows/" + workflowId + "/run", Map.of());
        String executionId = String.valueOf(run.getBody().get("executionId"));

        // the question went out with CALLBACK buttons, not links
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                WIRE_MOCK.findAll(postRequestedFor(urlPathMatching("/bot.*/sendMessage")))
                        .stream().map(r -> r.getBodyAsString())
                        .filter(b -> b.contains("Tap to deploy?")).findFirst())
                .hasValueSatisfying(body -> {
                    assertThat(body).contains("inline_keyboard").contains("apr:").contains("dny:");
                    assertThat(body).doesNotContain("/hooks/approval/");
                }));
        String body = WIRE_MOCK.findAll(postRequestedFor(urlPathMatching("/bot.*/sendMessage")))
                .stream().map(r -> r.getBodyAsString())
                .filter(b -> b.contains("Tap to deploy?")).findFirst().orElseThrow();
        Matcher matcher = CALLBACK.matcher(body);
        String approveToken = null;
        while (matcher.find()) {
            if ("apr".equals(matcher.group(1))) {
                approveToken = matcher.group(2);
            }
        }
        assertThat(approveToken).isNotNull();

        // inject the button tap: one update, then back to empty (scenario)
        String update = """
                {"ok":true,"result":[{"update_id":7,"callback_query":{
                  "id":"cb-1","data":"apr:%s",
                  "message":{"message_id":42,"chat":{"id":123}}}}]}
                """.formatted(approveToken);
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/getUpdates")).inScenario("tap")
                .whenScenarioStateIs(STARTED)
                .willReturn(okJson(update))
                .willSetStateTo("delivered"));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/getUpdates")).inScenario("tap")
                .whenScenarioStateIs("delivered")
                .willReturn(okJson("{\"ok\":true,\"result\":[]}")));

        // decision lands without any browser: execution completes, yes-branch runs
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Map<String, Object> execution = getExecution(executionId);
            assertThat(execution.get("status")).isEqualTo("SUCCEEDED");
        });
        WIRE_MOCK.verify(1, postRequestedFor(urlPathMatching("/deploy")));

        // user got the toast and the message lost its buttons
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            WIRE_MOCK.verify(postRequestedFor(urlPathMatching("/bot.*/answerCallbackQuery"))
                    .withRequestBody(containing("cb-1")));
            WIRE_MOCK.verify(postRequestedFor(urlPathMatching("/bot.*/editMessageText"))
                    .withRequestBody(containing("Tap to deploy?"))
                    .withRequestBody(containing("Approved")));
        });
    }

    @Test
    void secondTapGetsAlreadyDecidedToast() {
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage"))
                .willReturn(okJson("{\"ok\":true,\"result\":{\"message_id\":50}}")));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/answerCallbackQuery")).willReturn(okJson("{\"ok\":true}")));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/editMessageText")).willReturn(okJson("{\"ok\":true}")));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/getUpdates"))
                .willReturn(okJson("{\"ok\":true,\"result\":[]}")));

        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", """
                name: double-tap
                trigger:
                  webhook: { path: "double-tap" }
                steps:
                  - name: ask
                    action: approval
                    with: { text: "Double tap?", chat_id: "123" }
                """);
        String workflowId = String.valueOf(created.getBody().get("id"));
        postJson("/api/workflows/" + workflowId + "/run", Map.of());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                WIRE_MOCK.findAll(postRequestedFor(urlPathMatching("/bot.*/sendMessage")))
                        .stream().anyMatch(r -> r.getBodyAsString().contains("Double tap?"))).isTrue());
        String body = WIRE_MOCK.findAll(postRequestedFor(urlPathMatching("/bot.*/sendMessage")))
                .stream().map(r -> r.getBodyAsString())
                .filter(b -> b.contains("Double tap?")).findFirst().orElseThrow();
        Matcher matcher = CALLBACK.matcher(body);
        assertThat(matcher.find()).isTrue();
        String token = matcher.group(2);

        // two taps on the same button in one batch — second must answer "Already decided"
        String updates = """
                {"ok":true,"result":[
                  {"update_id":11,"callback_query":{"id":"cb-a","data":"apr:%s",
                   "message":{"message_id":50,"chat":{"id":123}}}},
                  {"update_id":12,"callback_query":{"id":"cb-b","data":"apr:%s",
                   "message":{"message_id":50,"chat":{"id":123}}}}
                ]}""".formatted(token, token);
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/getUpdates")).inScenario("double")
                .whenScenarioStateIs(STARTED)
                .willReturn(okJson(updates))
                .willSetStateTo("done"));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/getUpdates")).inScenario("double")
                .whenScenarioStateIs("done")
                .willReturn(okJson("{\"ok\":true,\"result\":[]}")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            WIRE_MOCK.verify(postRequestedFor(urlPathMatching("/bot.*/answerCallbackQuery"))
                    .withRequestBody(containing("cb-a")));
            WIRE_MOCK.verify(postRequestedFor(urlPathMatching("/bot.*/answerCallbackQuery"))
                    .withRequestBody(containing("cb-b"))
                    .withRequestBody(containing("Already decided")));
        });
    }
}
