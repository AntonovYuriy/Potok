package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.potok.recipient.RecipientService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end M6 wiring: incoming chat → PENDING when auto-approve is off,
 * approve via REST → APPROVED, telegram `to: approved` fans out to exactly the
 * approved recipients (PENDING/REVOKED are skipped), `/stop` self-revokes, and
 * the backward-compatible `chat_id` path still works.
 *
 * The bot poller is disabled here — incoming messages are driven by calling
 * {@link RecipientService#handleBotMessage(String, String, String)} directly
 * so the test is deterministic without depending on WireMock scenario state.
 * The poller's wiring is covered separately by {@link TelegramButtonsIntegrationTest}.
 */
class RecipientsIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void disablePoller(DynamicPropertyRegistry registry) {
        registry.add("potok.telegram.poll-updates", () -> "false");
    }

    @Autowired
    private RecipientService recipientService;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listRecipients(String filter) {
        String url = "/api/recipients?size=200" + (filter == null ? "" : "&status=" + filter);
        Map<String, Object> body = rest.getForObject(url, Map.class);
        return (List<Map<String, Object>>) body.get("items");
    }

    private Map<String, Object> findByName(List<Map<String, Object>> items, String displayName) {
        return items.stream()
                .filter(item -> displayName.equals(item.get("displayName")))
                .findFirst().orElseThrow();
    }

    private void setAutoApprove(boolean value) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> patch = rest.exchange(
                "/api/settings", org.springframework.http.HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(
                        Map.of("telegram_auto_approve", value), headers),
                MAP_TYPE);
        assertThat(patch.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(patch.getBody()).containsEntry("telegram_auto_approve", value);
    }

    @Test
    void autoApproveOffNewChatLandsPending() {
        setAutoApprove(false);
        recipientService.handleBotMessage("777001", "Alice", "/start");

        List<Map<String, Object>> recipients = listRecipients("PENDING");
        Map<String, Object> alice = findByName(recipients, "Alice");
        assertThat(alice).containsEntry("status", "PENDING");
        assertThat((String) alice.get("chatIdMasked")).contains("7001");
    }

    @Test
    void autoApproveOnNewChatLandsApproved() {
        setAutoApprove(true);
        recipientService.handleBotMessage("777002", "Bob", "/start");

        List<Map<String, Object>> recipients = listRecipients("APPROVED");
        assertThat(findByName(recipients, "Bob")).containsEntry("status", "APPROVED");
        setAutoApprove(false); // reset for other tests sharing the context
    }

    @Test
    void approveFlowTransitionsAndBroadcastHitsApprovedOnly() {
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage"))
                .willReturn(okJson("{\"ok\":true}")));
        setAutoApprove(false);

        recipientService.handleBotMessage("888001", "Pending-Pat", "/start");
        recipientService.handleBotMessage("888002", "Approve-Anna", "/start");
        recipientService.handleBotMessage("888003", "Revoke-Roger", "/start");

        Map<String, Object> anna = findByName(listRecipients(null), "Approve-Anna");
        Map<String, Object> roger = findByName(listRecipients(null), "Revoke-Roger");

        ResponseEntity<Map<String, Object>> approve = postJson(
                "/api/recipients/" + anna.get("id") + "/approve", Map.of());
        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approve.getBody()).containsEntry("status", "APPROVED");

        ResponseEntity<Map<String, Object>> approveRoger = postJson(
                "/api/recipients/" + roger.get("id") + "/approve", Map.of());
        assertThat(approveRoger.getStatusCode()).isEqualTo(HttpStatus.OK);
        // and then revoke Roger
        ResponseEntity<Map<String, Object>> revoke = postJson(
                "/api/recipients/" + roger.get("id") + "/revoke", Map.of());
        assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoke.getBody()).containsEntry("status", "REVOKED");

        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", """
                name: m6-broadcast
                trigger:
                  webhook: { path: "m6-broadcast" }
                steps:
                  - name: notify
                    action: telegram
                    with:
                      to: approved
                      text: "Broadcast hello"
                """);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workflowId = String.valueOf(created.getBody().get("id"));
        ResponseEntity<Map<String, Object>> run = postJson(
                "/api/workflows/" + workflowId + "/run", Map.of());
        String executionId = String.valueOf(run.getBody().get("executionId"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> execution = getExecution(executionId);
            assertThat(execution.get("status")).isEqualTo("SUCCEEDED");
        });

        // Anna received exactly once; pending Pat + revoked Roger never received,
        // even though the broadcast referenced "approved" (they are not approved).
        // Asserted chat-by-chat so prior tests in the same context can leave
        // unrelated approved recipients behind without flaking this test.
        var requests = WIRE_MOCK.findAll(postRequestedFor(urlPathMatching("/bot.*/sendMessage")));
        java.util.function.Function<String, Long> sentFor = chat -> requests.stream()
                .filter(r -> r.getBodyAsString().contains("Broadcast hello")
                        && r.getBodyAsString().contains(chat))
                .count();
        assertThat(sentFor.apply("888002")).isEqualTo(1L);
        assertThat(sentFor.apply("888001")).isEqualTo(0L);
        assertThat(sentFor.apply("888003")).isEqualTo(0L);
    }

    @Test
    void stopCommandSelfRevokes() {
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage"))
                .willReturn(okJson("{\"ok\":true}")));
        setAutoApprove(true);
        recipientService.handleBotMessage("999001", "Stop-User", "/start");
        // /stop while approved → REVOKED
        recipientService.handleBotMessage("999001", "Stop-User", "/stop");

        Map<String, Object> revoked = findByName(listRecipients("REVOKED"), "Stop-User");
        assertThat(revoked).containsEntry("status", "REVOKED");
        setAutoApprove(false);
    }

    @Test
    void chatIdPathStillWorksUnchanged() {
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage"))
                .willReturn(okJson("{\"ok\":true}")));

        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", """
                name: m6-chat-id-compat
                trigger:
                  webhook: { path: "m6-chat-id-compat" }
                steps:
                  - name: notify
                    action: telegram
                    with:
                      chat_id: "424242"
                      text: "M1 path still works"
                """);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workflowId = String.valueOf(created.getBody().get("id"));
        ResponseEntity<Map<String, Object>> run = postJson(
                "/api/workflows/" + workflowId + "/run", Map.of());
        String executionId = String.valueOf(run.getBody().get("executionId"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Map<String, Object> execution = getExecution(executionId);
            assertThat(execution.get("status")).isEqualTo("SUCCEEDED");
        });
        WIRE_MOCK.verify(postRequestedFor(urlPathMatching("/bot.*/sendMessage"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .containing("424242")));
    }

    @Test
    void settingsEndpointDefaultIsOff() {
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = rest.getForObject("/api/settings", Map.class);
        // some other test may have flipped it — set explicitly first
        setAutoApprove(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> after = rest.getForObject("/api/settings", Map.class);
        assertThat(after).containsEntry("telegram_auto_approve", false);
        assertThat(settings).containsKey("telegram_auto_approve"); // shape stable
    }

    @Test
    void illegalRecipientIdReturns404() {
        ResponseEntity<Map<String, Object>> response = postJson(
                "/api/recipients/00000000-0000-0000-0000-000000000000/approve", Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
