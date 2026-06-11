package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every shipped example must stay valid against the current parser, and the
 * Help gallery must cover exactly the files that exist.
 */
class ExamplesIntegrationTest extends IntegrationTestBase {

    @Test
    void allExampleYamlsCreateSuccessfully() throws Exception {
        try (Stream<Path> files = Files.list(Path.of("examples"))) {
            List<Path> yamls = files.filter(p -> p.toString().endsWith(".yaml")).toList();
            assertThat(yamls).hasSizeGreaterThanOrEqualTo(7);

            for (Path yaml : yamls) {
                ResponseEntity<Map<String, Object>> created =
                        postYaml("/api/workflows", Files.readString(yaml));
                assertThat(created.getStatusCode())
                        .as("example %s must be valid", yaml.getFileName())
                        .isEqualTo(HttpStatus.CREATED);
                // disable so cron/poll examples don't run against real targets mid-suite
                rest.delete("/api/workflows/" + created.getBody().get("id"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void helpGalleryMatchesShippedExamples() {
        List<Map<String, Object>> templates =
                rest.getForObject("/help/templates.json", List.class);
        assertThat(templates).hasSizeGreaterThanOrEqualTo(8);

        for (Map<String, Object> t : templates) {
            assertThat(t).containsKeys("id", "title", "problem", "trigger", "actions", "file", "sample", "default_name", "params");
            // the YAML each card imports is served from the jar
            ResponseEntity<String> yaml =
                    rest.getForEntity("/help/examples/" + t.get("file"), String.class);
            assertThat(yaml.getStatusCode())
                    .as("template %s file", t.get("id"))
                    .isEqualTo(HttpStatus.OK);
            assertThat(yaml.getBody()).contains("name:").contains("trigger:");
            // the parameterized template behind the form is served too
            assertThat(rest.getForEntity("/help/templates/" + t.get("id") + ".yaml.tpl", String.class)
                    .getStatusCode()).as("tpl asset %s", t.get("id")).isEqualTo(HttpStatus.OK);
        }

        // reference asset present and structured
        Map<String, Object> reference = rest.getForObject("/help/reference.json", Map.class);
        assertThat(reference).containsKeys("triggers", "stepFields", "conditions", "templating", "actions");
    }
}
