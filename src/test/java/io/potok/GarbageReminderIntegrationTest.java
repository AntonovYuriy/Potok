package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The examples/garbage-reminder.yaml shape end-to-end: warsaw_waste step against
 * a WireMock stub of warszawa19115.pl, telegram alert when something is
 * collected tomorrow, SKIPPED when not.
 */
class GarbageReminderIntegrationTest extends IntegrationTestBase {

    private static String apiJson(String collectionDate) {
        return """
                [{"adres":"TEST 1","dzielnicy":"Test","harmonogramy":[],"harmonogramyN":[],
                  "harmonogramyZ":[
                    {"data":"%s","frakcja":{"id_frakcja":"OP","nazwa":"opakowania z papieru i tektury"}},
                    {"data":"%s","frakcja":{"id_frakcja":"ZM","nazwa":"odpady zmieszane"}},
                    {"data":"2099-01-01","frakcja":{"id_frakcja":"OS","nazwa":"opakowania ze szkła"}}
                  ]}]
                """.formatted(collectionDate, collectionDate);
    }

    private void stubApiAndTelegram(String collectionDate) {
        // content-type text/html mirrors the real endpoint
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/harmonogramy-wywozu-odpadow"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/html;charset=UTF-8")
                        .withBody(apiJson(collectionDate))));
        WIRE_MOCK.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlEqualTo("/bottest-token/sendMessage"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\": true}")));
    }

    private String createAndRun(String name, String path) {
        var created = postYaml("/api/workflows", """
                name: %s
                trigger:
                  webhook: { path: "%s" }
                steps:
                  - name: schedule
                    action: warsaw_waste
                    with:
                      address_point_id: "27086987"
                      base_url: "%s"
                  - name: notify
                    if: "{{ steps.schedule.tomorrow_count != 0 }}"
                    action: telegram
                    with:
                      chat_id: "42"
                      text: "🗑️ Завтра вывоз ({{ steps.schedule.tomorrow_date }}): {{ steps.schedule.summary }}"
                """.formatted(name, path, WIRE_MOCK.baseUrl()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) postJson("/hooks/" + path, Map.of()).getBody().get("executionId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsSummaryWhenCollectionIsTomorrow() {
        String tomorrow = LocalDate.now(ZoneId.of("Europe/Warsaw")).plusDays(1).toString();
        stubApiAndTelegram(tomorrow);

        String executionId = createAndRun("garbage-tomorrow", "garbage-tomorrow");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) getExecution(executionId).get("steps");
        Map<String, Object> schedule = steps.get(0);
        Map<String, Object> output = (Map<String, Object>) schedule.get("output");
        assertThat(output.get("tomorrow_count")).isEqualTo(2);
        assertThat(output.get("summary")).isEqualTo("Papier, Zmieszane");
        assertThat(steps.get(1).get("status")).isEqualTo("SUCCEEDED");

        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo("/bottest-token/sendMessage"))
                .withRequestBody(containing("Завтра вывоз (" + tomorrow + "): Papier, Zmieszane")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsNotifyWhenNothingTomorrow() {
        String farFuture = LocalDate.now(ZoneId.of("Europe/Warsaw")).plusDays(7).toString();
        stubApiAndTelegram(farFuture);

        String executionId = createAndRun("garbage-quiet", "garbage-quiet");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) getExecution(executionId).get("steps");
        assertThat(steps.get(1).get("status")).isEqualTo("SKIPPED");
        WIRE_MOCK.verify(0, postRequestedFor(urlEqualTo("/bottest-token/sendMessage")));
    }
}
