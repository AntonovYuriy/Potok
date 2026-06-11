package io.potok.preview;

import io.potok.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewTextsTest {

    @Test
    void humanizesCommonErrors() {
        assertThat(PreviewTexts.humanizeError(
                "http GET http://x failed: HttpConnectTimeoutException: connect timed out"))
                .isEqualTo("The request timed out");
        assertThat(PreviewTexts.humanizeError("UnknownHostException: shop.example.invalid"))
                .isEqualTo("Host not found — check the URL");
        assertThat(PreviewTexts.humanizeError("ConnectException: Connection refused"))
                .isEqualTo("Couldn't reach the server");
        assertThat(PreviewTexts.humanizeError(
                "refusing to call 'x': it resolves to a private/internal address (10.0.0.1)..."))
                .isEqualTo("URL blocked: it points to a private/internal address");
        assertThat(PreviewTexts.humanizeError("something exotic")).isEqualTo("Step failed");
        assertThat(PreviewTexts.humanizeError(null)).isEqualTo("Step failed");
    }

    @Test
    void triggerNotesArePlainLanguage() {
        var changed = new WorkflowDefinition.Trigger(null, null,
                new WorkflowDefinition.Poll(Duration.ofMinutes(10), Map.of("url", "http://x"),
                        "changed", null), null);
        assertThat(PreviewTexts.triggerNote(changed))
                .isEqualTo("You'll get a message when this value CHANGES between checks (every 10m).");

        var expression = new WorkflowDefinition.Trigger(null, null,
                new WorkflowDefinition.Poll(Duration.ofSeconds(30), Map.of("url", "http://x"),
                        "{{ poll.value < 200 }}", null), null);
        assertThat(PreviewTexts.triggerNote(expression))
                .contains("becomes true").contains("every 30s");

        var rss = new WorkflowDefinition.Trigger(null, null, null,
                new WorkflowDefinition.Rss(Duration.ofHours(1), "http://x/feed"));
        assertThat(PreviewTexts.triggerNote(rss)).contains("NEW feed item").contains("every 1h");

        var cron = new WorkflowDefinition.Trigger("0 7 * * *", null, null, null);
        assertThat(PreviewTexts.triggerNote(cron)).contains("0 7 * * *");

        var webhook = new WorkflowDefinition.Trigger(null,
                new WorkflowDefinition.Webhook("deploy", null), null, null);
        assertThat(PreviewTexts.triggerNote(webhook)).contains("webhook");
    }

    @Test
    void summariesReadFriendly() {
        assertThat(PreviewTexts.httpSummary("GET", "http://x/health", 200))
                .isEqualTo("GET http://x/health — 200 OK");
        assertThat(PreviewTexts.httpSummary("GET", "http://x/health", 503))
                .isEqualTo("GET http://x/health — status 503");
        assertThat(PreviewTexts.telegramSummary("Цена упала: 249 zł"))
                .isEqualTo("Message that WOULD be sent (not sent in preview): \"Цена упала: 249 zł\"");
        assertThat(PreviewTexts.simulatedHttpSummary("POST", "http://x/api"))
                .isEqualTo("Would send POST to http://x/api (not sent in preview)");
        assertThat(PreviewTexts.conditionSummary("telegram", "price < 200", 249))
                .contains("NOT met right now").contains("current: 249")
                .contains("no message would be sent");
        assertThat(PreviewTexts.conditionSummary("http", "x == 1", null))
                .contains("would be skipped").doesNotContain("current:");
    }

    @Test
    void pollFetchSummaryReportsSelectorMisses() {
        var css = new WorkflowDefinition.Extract(null, "span.availability");
        assertThat(PreviewTexts.pollFetchSummary(200, true, "In stock!", css))
                .isEqualTo("Fetched — status 200, element found: \"In stock!\"");
        assertThat(PreviewTexts.pollFetchSummary(200, true, null, css))
                .contains("matched NOTHING").contains("span.availability");
        assertThat(PreviewTexts.pollFetchSummary(200, false, null, null))
                .isEqualTo("Fetched — status 200");
    }

    @Test
    void truncateCapsLongStringsRecursively() {
        String longText = "a".repeat(5_000);
        Object result = PreviewTexts.truncate(Map.of("body", longText), 4_000);
        @SuppressWarnings("unchecked")
        String body = (String) ((Map<String, Object>) result).get("body");
        assertThat(body).hasSizeLessThan(4_100).endsWith("… (truncated)");
        assertThat(PreviewTexts.truncate("short", 100)).isEqualTo("short");
        assertThat(PreviewTexts.truncate(42, 100)).isEqualTo(42);
    }
}
