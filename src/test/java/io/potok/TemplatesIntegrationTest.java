package io.potok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.potok.template.TemplateRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Templates are the single source of truth. For every manifest entry:
 * the .tpl exists, rendering it with the manifest defaults reproduces the
 * committed example byte-for-byte (drift guard), and the result is accepted
 * by the live create API. Replaces the old examples-validity test.
 */
class TemplatesIntegrationTest extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode manifest() throws Exception {
        return MAPPER.readTree(Files.readString(
                Path.of("src/main/resources/static/help/templates.json")));
    }

    @Test
    void everyTemplateRendersMatchesExampleAndCreates() throws Exception {
        JsonNode manifest = manifest();
        assertThat(manifest.size()).isGreaterThanOrEqualTo(15);

        for (JsonNode entry : manifest) {
            String id = entry.path("id").asText();
            Path tpl = Path.of("templates", id + ".yaml.tpl");
            assertThat(tpl).as("template file for %s", id).exists();

            String rendered = TemplateRenderer.render(
                    Files.readString(tpl), TemplateRenderer.defaults(entry));
            assertThat(rendered).as("no unrendered params in %s", id)
                    .doesNotContain("{{param.");

            // drift guard: committed example == template + defaults
            Path example = Path.of("examples", entry.path("file").asText());
            assertThat(rendered)
                    .as("examples/%s must equal rendered template %s — run ./gradlew renderExamples",
                            entry.path("file").asText(), id)
                    .isEqualTo(Files.readString(example));

            // and the live API accepts it
            ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", rendered);
            assertThat(created.getStatusCode())
                    .as("rendered template %s must create", id)
                    .isEqualTo(HttpStatus.CREATED);
            rest.delete("/api/workflows/" + created.getBody().get("id"));
        }
    }

    @Test
    void manifestEntriesAreFormReady() throws Exception {
        Set<String> ids = new HashSet<>();
        for (JsonNode entry : manifest()) {
            String id = entry.path("id").asText();
            assertThat(ids.add(id)).as("unique id %s", id).isTrue();
            assertThat(entry.path("default_name").asText()).as("%s default_name", id).isNotEmpty();
            assertThat(entry.path("params").size()).as("%s has params", id).isGreaterThan(0);
            for (JsonNode param : entry.path("params")) {
                assertThat(param.path("key").asText()).isNotEmpty();
                assertThat(param.path("label").asText()).isNotEmpty();
                String type = param.path("type").asText();
                assertThat(type).isIn("url", "string", "duration", "cron", "number", "text", "select", "env");
                if (type.equals("env")) {
                    assertThat(param.path("env").asText()).isNotEmpty();
                } else {
                    assertThat(param.hasNonNull("default")).as("%s.%s default", id, param.path("key")).isTrue();
                }
                if (type.equals("select")) {
                    assertThat(param.path("options").isArray())
                            .as("%s.%s options", id, param.path("key")).isTrue();
                    assertThat(param.path("options").size()).isGreaterThan(1);
                    boolean defaultInOptions = false;
                    for (JsonNode option : param.path("options")) {
                        defaultInOptions |= option.asText().equals(param.path("default").asText());
                    }
                    assertThat(defaultInOptions)
                            .as("%s.%s default must be one of the options", id, param.path("key")).isTrue();
                }
            }
        }
    }

    /** Quotes in user values must not break the rendered YAML or the condition. */
    @Test
    void keywordWithQuotesRendersValidWorkflow() throws Exception {
        String tpl = Files.readString(Path.of("templates", "keyword-on-page.yaml.tpl"));
        for (String keyword : new String[]{"O'Brien", "say \"hi\"", "mix 'of\" both"}) {
            String rendered = TemplateRenderer.render(tpl, Map.of(
                    "name", "kw-quotes", "url", "https://example.com/",
                    "keyword", keyword, "interval", "30m"));
            ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", rendered);
            assertThat(created.getStatusCode())
                    .as("keyword %s must render a valid workflow", keyword)
                    .isEqualTo(HttpStatus.CREATED);
            rest.delete("/api/workflows/" + created.getBody().get("id"));
        }
    }

    /** Gallery is ordered by simplicity; the local-specific custom-action example goes last. */
    @Test
    void galleryIsOrderedBySimplicity() throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        manifest().forEach(entry -> ids.add(entry.path("id").asText()));
        assertThat(ids).containsExactly(
                "simple-reminder", "follow-up-reminder", "json-threshold", "email-alert",
                "multi-channel-alert", "confirm-before-act", "keyword-on-page", "price-drop",
                "monthly-payment-reminder", "uptime-monitor", "release-watcher", "rss-digest",
                "availability-watcher", "series-episode-watcher", "price-alert", "ssl-expiry",
                "github-notify", "garbage-reminder");
    }
}
