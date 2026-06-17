package io.potok;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives the recipient ingest through the REAL {@link io.potok.trigger.TelegramUpdatesPoller}
 * (poll-updates=true). WireMock returns a sequence of getUpdates responses: a
 * `message` update from an unknown chat then empty results. The poller must
 * upsert the recipient as PENDING (auto-approve off), refresh last_seen on a
 * second message, and pass an increasing {@code offset} so Telegram never
 * re-delivers an already-handled update.
 *
 * Coverage gap previously: the message-update JSON dispatch ({@code
 * handleMessage(JsonNode)}) was only exercised indirectly via
 * {@code RecipientService.handleBotMessage}. This test pins the JSON path.
 */
class TelegramRecipientIngestIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void ingestProperties(DynamicPropertyRegistry registry) {
        registry.add("potok.telegram.poll-updates", () -> "true");
        // tight idle so the awaits do not stretch the test runtime
        registry.add("potok.telegram.updates-idle", () -> "PT0.05S");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> waitForRecipient(String displayName) {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            Map<String, Object> body = rest.getForObject(
                    "/api/recipients?size=200", Map.class);
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
            assertThat(items).anyMatch(r -> displayName.equals(r.get("displayName")));
        });
        Map<String, Object> body = rest.getForObject("/api/recipients?size=200", Map.class);
        return ((List<Map<String, Object>>) body.get("items")).stream()
                .filter(r -> displayName.equals(r.get("displayName")))
                .findFirst().orElseThrow();
    }

    private static String messageUpdate(long updateId, long chatId, String username,
                                        String firstName, String text) {
        return ("""
                {"ok":true,"result":[{"update_id":%d,"message":{
                  "message_id":%d,
                  "chat":{"id":%d,"type":"private","username":"%s","first_name":"%s"},
                  "from":{"id":%d,"username":"%s","first_name":"%s"},
                  "text":"%s"}}]}
                """).formatted(updateId, updateId, chatId, username, firstName,
                chatId, username, firstName, text);
    }

    /**
     * @return the highest offset seen across all getUpdates requests (offset is
     *         the value the poller sent — Telegram acks updates with id &lt; offset).
     */
    private long maxObservedOffset() {
        List<LoggedRequest> requests = WIRE_MOCK
                .findAll(postRequestedFor(urlPathMatching("/bot.*/getUpdates")));
        long max = -1;
        for (LoggedRequest req : requests) {
            String body = req.getBodyAsString();
            int idx = body.indexOf("\"offset\":");
            if (idx < 0) {
                continue;
            }
            int start = idx + "\"offset\":".length();
            int end = start;
            while (end < body.length() && (Character.isDigit(body.charAt(end))
                    || body.charAt(end) == '-')) {
                end++;
            }
            try {
                max = Math.max(max, Long.parseLong(body.substring(start, end)));
            } catch (NumberFormatException ignored) { }
        }
        return max;
    }

    @Test
    void unknownChatMessagePathLandsPendingAndOffsetAdvances() {
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage"))
                .willReturn(okJson("{\"ok\":true}")));
        // first poll returns ONE message update; subsequent polls are empty
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/getUpdates")).inScenario("ingest")
                .whenScenarioStateIs(STARTED)
                .willReturn(okJson(messageUpdate(101, 555_001, "alice_t", "Alice", "/start")))
                .willSetStateTo("delivered"));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/getUpdates")).inScenario("ingest")
                .whenScenarioStateIs("delivered")
                .willReturn(okJson("{\"ok\":true,\"result\":[]}")));

        Map<String, Object> alice = waitForRecipient("@alice_t");
        assertThat(alice).containsEntry("status", "PENDING");
        assertThat((String) alice.get("chatIdMasked")).contains("5001");

        // bot must have replied to the chat (pending greeting). At least one
        // sendMessage call referencing the chat id was made.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            long replies = WIRE_MOCK
                    .findAll(postRequestedFor(urlPathMatching("/bot.*/sendMessage")))
                    .stream()
                    .filter(r -> r.getBodyAsString().contains("\"chat_id\":\"555001\""))
                    .count();
            assertThat(replies).isGreaterThanOrEqualTo(1L);
        });

        // offset advances past update_id=101 → poller will never re-handle it
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(maxObservedOffset()).isGreaterThanOrEqualTo(102L));
    }

    /**
     * Two messages from the same chat in a single getUpdates batch. The chat_id
     * unique constraint plus the upsert means exactly one row exists. The
     * offset advances past BOTH update ids, so the same updates never get
     * reprocessed after a restart. (last_seen refresh on repeat is pinned by
     * the unit-level `repeatedMessageKeepsExistingStatus` in RecipientServiceTest;
     * asserting it again here against wall-clock time would race.)
     */
    @Test
    void repeatMessagesUpsertExactlyOneRowAndAdvanceOffsetPastBoth() {
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage"))
                .willReturn(okJson("{\"ok\":true}")));
        String batch = """
                {"ok":true,"result":[
                  {"update_id":201,"message":{"message_id":201,
                    "chat":{"id":555002,"type":"private","username":"bob_t","first_name":"Bob"},
                    "from":{"id":555002,"username":"bob_t","first_name":"Bob"},
                    "text":"/start"}},
                  {"update_id":202,"message":{"message_id":202,
                    "chat":{"id":555002,"type":"private","username":"bob_t","first_name":"Bob"},
                    "from":{"id":555002,"username":"bob_t","first_name":"Bob"},
                    "text":"hi again"}}
                ]}""";
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/getUpdates")).inScenario("repeat")
                .whenScenarioStateIs(STARTED)
                .willReturn(okJson(batch))
                .willSetStateTo("delivered"));
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/getUpdates")).inScenario("repeat")
                .whenScenarioStateIs("delivered")
                .willReturn(okJson("{\"ok\":true,\"result\":[]}")));

        Map<String, Object> bob = waitForRecipient("@bob_t");
        assertThat(bob).containsEntry("status", "PENDING");

        // exactly one row for this chat — repeat did not insert a second
        @SuppressWarnings("unchecked")
        Map<String, Object> body = rest.getForObject("/api/recipients?size=200", Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        long bobRows = items.stream()
                .filter(r -> "@bob_t".equals(r.get("displayName"))).count();
        assertThat(bobRows).isEqualTo(1L);

        // offset advanced past BOTH updates → neither will be re-handled
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(maxObservedOffset()).isGreaterThanOrEqualTo(203L));
    }
}
