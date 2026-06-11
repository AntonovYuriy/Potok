package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/preview: dry run with real read-only fetches, simulated side
 * effects, nothing persisted. The preview budget is shrunk to 2s so the
 * timeout path completes quickly.
 */
class PreviewIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void previewProperties(DynamicPropertyRegistry registry) {
        registry.add("potok.preview.timeout", () -> "PT2S");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> preview(String yaml) {
        ResponseEntity<Map<String, Object>> response = postYaml("/api/preview", yaml);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("preview response: " + response.getBody()).isTrue();
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("steps");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> trigger(Map<String, Object> result) {
        return (Map<String, Object>) result.get("trigger");
    }

    private String availabilityYaml() {
        return """
                name: pv-availability
                trigger:
                  poll:
                    interval: 1h
                    http:
                      url: %s/page
                    extract:
                      css: "span.availability"
                    fire_when: changed
                steps:
                  - name: notify
                    action: telegram
                    with:
                      chat_id: "123"
                      text: "👀 Наличие изменилось: {{ trigger.value }}"
                """.formatted(WIRE_MOCK.baseUrl());
    }

    @Test
    void pollPreviewExecutesFetchAndSimulatesTelegram() {
        WIRE_MOCK.stubFor(get("/page").willReturn(
                ok("<html><body><span class=\"availability\">In stock!</span></body></html>")));

        Map<String, Object> result = preview(availabilityYaml());

        Map<String, Object> trigger = trigger(result);
        assertThat(trigger.get("kind")).isEqualTo("poll");
        assertThat((String) trigger.get("human_summary")).contains("element found: \"In stock!\"");
        assertThat((String) trigger.get("note")).contains("CHANGES").contains("every 1h");

        Map<String, Object> notify = steps(result).get(0);
        assertThat(notify.get("mode")).isEqualTo("simulated");
        assertThat((String) notify.get("human_summary"))
                .contains("WOULD be sent").contains("In stock!");

        // the whole point: telegram was never called
        WIRE_MOCK.verify(0, postRequestedFor(urlPathMatching("/bot.*/sendMessage")));
    }

    @Test
    void pollPreviewReportsSelectorMiss() {
        WIRE_MOCK.stubFor(get("/page").willReturn(ok("<html><body>no badge here</body></html>")));

        Map<String, Object> trigger = trigger(preview(availabilityYaml()));
        assertThat((String) trigger.get("human_summary"))
                .contains("matched NOTHING").contains("span.availability");
    }

    private String thresholdYaml() {
        return """
                name: pv-threshold
                trigger:
                  cron: "0 7 * * *"
                steps:
                  - name: fetch
                    action: http
                    with:
                      url: %s/price
                  - name: notify
                    action: telegram
                    if: "{{ steps.fetch.body.price < 200 }}"
                    with:
                      chat_id: "123"
                      text: "Цена упала: {{ steps.fetch.body.price }}"
                """.formatted(WIRE_MOCK.baseUrl());
    }

    @Test
    void conditionNotMetReportsCurrentValueAndSkips() {
        WIRE_MOCK.stubFor(get("/price").willReturn(okJson("{\"price\": 249}")));

        List<Map<String, Object>> steps = steps(preview(thresholdYaml()));

        Map<String, Object> fetch = steps.get(0);
        assertThat(fetch.get("mode")).isEqualTo("executed");
        assertThat((String) fetch.get("human_summary")).contains("200 OK");

        Map<String, Object> notify = steps.get(1);
        assertThat(notify.get("mode")).isEqualTo("skipped");
        assertThat((String) notify.get("human_summary"))
                .contains("NOT met right now")
                .contains("current: 249")
                .contains("no message would be sent");
        WIRE_MOCK.verify(0, postRequestedFor(urlPathMatching("/bot.*/sendMessage")));
    }

    @Test
    void conditionMetRendersTheMessage() {
        WIRE_MOCK.stubFor(get("/price").willReturn(okJson("{\"price\": 149}")));

        Map<String, Object> notify = steps(preview(thresholdYaml())).get(1);
        assertThat(notify.get("mode")).isEqualTo("simulated");
        assertThat((String) notify.get("human_summary")).contains("Цена упала: 149");
        WIRE_MOCK.verify(0, postRequestedFor(urlPathMatching("/bot.*/sendMessage")));
    }

    @Test
    void nonGetHttpIsSimulatedNotSent() {
        Map<String, Object> result = preview("""
                name: pv-post
                trigger:
                  cron: "0 7 * * *"
                steps:
                  - name: ping
                    action: http
                    with:
                      method: POST
                      url: %s/hook
                      body: '{"deploy": true}'
                """.formatted(WIRE_MOCK.baseUrl()));

        Map<String, Object> ping = steps(result).get(0);
        assertThat(ping.get("mode")).isEqualTo("simulated");
        assertThat((String) ping.get("human_summary")).contains("Would send POST");
        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = (Map<String, Object>) ping.get("rendered_output");
        assertThat(rendered.get("method")).isEqualTo("POST");
        assertThat((String) rendered.get("body")).contains("deploy");
        WIRE_MOCK.verify(0, postRequestedFor(urlPathMatching("/hook")));
    }

    @Test
    void slowResponseHitsThePreviewTimeout() {
        WIRE_MOCK.stubFor(get("/slow").willReturn(aResponse().withFixedDelay(3_000).withBody("late")));

        Map<String, Object> result = preview("""
                name: pv-slow
                trigger:
                  cron: "0 7 * * *"
                steps:
                  - name: fetch
                    action: http
                    with:
                      url: %s/slow
                  - name: notify
                    action: telegram
                    with:
                      chat_id: "123"
                      text: "{{ steps.fetch.body }}"
                """.formatted(WIRE_MOCK.baseUrl()));

        List<Map<String, Object>> steps = steps(result);
        assertThat(steps.get(0).get("mode")).isEqualTo("failed");
        assertThat((String) steps.get(0).get("human_summary")).isEqualTo("The request timed out");
        // downstream of the failed fetch is reported, not silently dropped
        assertThat(steps.get(1).get("mode")).isEqualTo("skipped");
        assertThat((String) steps.get(1).get("human_summary")).contains("depends on failed step");
    }

    @Test
    void failedStepIsHumanFirstWithTechnicalDetail() {
        Map<String, Object> result = preview("""
                name: pv-unreachable
                trigger:
                  cron: "0 7 * * *"
                steps:
                  - name: fetch
                    action: http
                    with:
                      url: http://definitely-not-a-host.invalid/
                """);

        Map<String, Object> fetch = steps(result).get(0);
        assertThat(fetch.get("mode")).isEqualTo("failed");
        assertThat((String) fetch.get("human_summary")).isEqualTo("Host not found — check the URL");
        assertThat((String) fetch.get("detail")).contains("definitely-not-a-host.invalid");
    }

    @Test
    void previewPersistsNothing() {
        WIRE_MOCK.stubFor(get("/price").willReturn(okJson("{\"price\": 149}")));
        int workflowsBefore = rest.getForObject("/api/workflows", List.class).size();
        int executionsBefore = rest.getForObject("/api/executions", List.class).size();

        preview(thresholdYaml());

        assertThat(rest.getForObject("/api/workflows", List.class)).hasSize(workflowsBefore);
        assertThat(rest.getForObject("/api/executions", List.class)).hasSize(executionsBefore);
    }

    @Test
    void invalidYamlGetsTheSameValidationErrorAsCreate() {
        ResponseEntity<Map<String, Object>> response = postYaml("/api/preview", """
                name: pv-bad
                trigger:
                  cron: "0 7 * * *"
                steps:
                  - name: fetch
                    action: http
                    if: "{{ steps.x.status == 200 && }}"
                    with:
                      url: http://example.com/
                """);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((String) response.getBody().get("detail")).contains("fetch");
    }

    /** Renders a catalog template with given param values — the form's exact code path. */
    private String renderTemplate(String id, Map<String, String> values) throws Exception {
        String tpl = java.nio.file.Files.readString(java.nio.file.Path.of("templates", id + ".yaml.tpl"));
        return io.potok.template.TemplateRenderer.render(tpl, values);
    }

    @Test
    void jsonThresholdTemplatePreviewsAgainstLiveData() throws Exception {
        WIRE_MOCK.stubFor(get("/rates").willReturn(okJson("{\"rates\":[{\"mid\": 4.42}]}")));

        Map<String, Object> result = preview(renderTemplate("json-threshold", Map.of(
                "name", "pv-json-threshold", "url", WIRE_MOCK.baseUrl() + "/rates",
                "jsonpath", "$.rates[0].mid", "comparison", ">",
                "threshold", "4.30", "interval", "1h")));

        Map<String, Object> trigger = trigger(result);
        assertThat((String) trigger.get("human_summary"))
                .contains("4.42").contains("Fire condition is TRUE right now");
        Map<String, Object> notify = steps(result).get(0);
        assertThat(notify.get("mode")).isEqualTo("simulated");
        assertThat((String) notify.get("human_summary")).contains("The value is now 4.42");
        WIRE_MOCK.verify(0, postRequestedFor(urlPathMatching("/bot.*/sendMessage")));
    }

    @Test
    void keywordOnPageTemplatePreviewsBothStates() throws Exception {
        String yaml = renderTemplate("keyword-on-page", Map.of(
                "name", "pv-keyword", "url", WIRE_MOCK.baseUrl() + "/tour",
                "keyword", "Warsaw", "interval", "30m"));

        WIRE_MOCK.stubFor(get("/tour").willReturn(ok("<html><body>Berlin, Prague</body></html>")));
        assertThat((String) trigger(preview(yaml)).get("human_summary"))
                .contains("Fire condition is NOT met right now");

        WIRE_MOCK.stubFor(get("/tour").willReturn(ok("<html><body>Berlin, Warsaw, Prague</body></html>")));
        assertThat((String) trigger(preview(yaml)).get("human_summary"))
                .contains("Fire condition is TRUE right now");
    }

    @Test
    void priceDropTemplateParsesThePriceTagNumerically() throws Exception {
        WIRE_MOCK.stubFor(get("/product").willReturn(
                ok("<html><body><span class=\"price\">189,99 zł</span></body></html>")));

        Map<String, Object> result = preview(renderTemplate("price-drop", Map.of(
                "name", "pv-price-drop", "url", WIRE_MOCK.baseUrl() + "/product",
                "selector", "span.price", "threshold", "200", "interval", "30m")));

        Map<String, Object> trigger = trigger(result);
        // "249,99 zł"-style tag parsed to a number and compared against the threshold
        assertThat((String) trigger.get("human_summary"))
                .contains("element found: \"189.99\"")
                .contains("Fire condition is TRUE right now");
        assertThat((String) steps(result).get(0).get("human_summary"))
                .contains("Price dropped to 189.99");
    }

    @Test
    void rejectsMoreThanTenSteps() {
        StringBuilder yaml = new StringBuilder("""
                name: pv-too-big
                trigger:
                  cron: "0 7 * * *"
                steps:
                """);
        for (int i = 1; i <= 11; i++) {
            yaml.append("""
                      - name: s%d
                        action: http
                        with:
                          url: http://example.com/
                    """.formatted(i));
        }
        ResponseEntity<Map<String, Object>> response = postYaml("/api/preview", yaml.toString());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((String) response.getBody().get("detail")).contains("at most 10");
    }
}
