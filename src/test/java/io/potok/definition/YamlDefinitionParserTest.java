package io.potok.definition;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlDefinitionParserTest {

    private final YamlDefinitionParser parser = new YamlDefinitionParser();

    @Test
    void parsesCronWorkflow() {
        WorkflowDefinition definition = parser.parse("""
                name: garbage-reminder
                trigger:
                  cron: "0 19 * * *"
                steps:
                  - name: fetch
                    action: http
                    with: { method: GET, url: "https://example.com" }
                  - name: notify
                    if: "{{ steps.fetch.status == 200 }}"
                    action: telegram
                    with: { chat_id: "123", text: "hi" }
                """);

        assertThat(definition.name()).isEqualTo("garbage-reminder");
        assertThat(definition.trigger().cron()).isEqualTo("0 19 * * *");
        assertThat(definition.trigger().webhook()).isNull();
        assertThat(definition.steps()).hasSize(2);
        assertThat(definition.steps().get(0).action()).isEqualTo("http");
        assertThat(definition.steps().get(0).with()).containsEntry("method", "GET");
        assertThat(definition.steps().get(1).condition()).isEqualTo("{{ steps.fetch.status == 200 }}");
    }

    @Test
    void parsesWebhookWorkflow() {
        WorkflowDefinition definition = parser.parse("""
                name: gh
                trigger:
                  webhook: { path: "gh-events" }
                steps:
                  - name: fetch
                    action: http
                    with: { method: GET, url: "https://example.com" }
                """);

        assertThat(definition.trigger().webhook().path()).isEqualTo("gh-events");
        assertThat(definition.trigger().cron()).isNull();
    }

    @Test
    void parsesMaxAttempts() {
        WorkflowDefinition definition = parser.parse("""
                name: retry
                trigger:
                  webhook: { path: "r" }
                steps:
                  - name: fetch
                    action: http
                    max_attempts: 5
                """);

        assertThat(definition.steps().get(0).maxAttempts()).isEqualTo(5);
    }

    @Test
    void stepLookupAndNextStep() {
        WorkflowDefinition definition = parser.parse("""
                name: chain
                trigger:
                  webhook: { path: "c" }
                steps:
                  - { name: a, action: http }
                  - { name: b, action: http }
                """);

        assertThat(definition.step("a").name()).isEqualTo("a");
        assertThat(definition.nextStep("a").name()).isEqualTo("b");
        assertThat(definition.nextStep("b")).isNull();
    }

    @Test
    void rejectsMissingName() {
        assertThatThrownBy(() -> parser.parse("""
                trigger:
                  cron: "0 0 * * *"
                steps:
                  - { name: a, action: http }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsBothTriggerTypes() {
        assertThatThrownBy(() -> parser.parse("""
                name: x
                trigger:
                  cron: "0 0 * * *"
                  webhook: { path: "x" }
                steps:
                  - { name: a, action: http }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsInvalidCron() {
        assertThatThrownBy(() -> parser.parse("""
                name: x
                trigger:
                  cron: "not a cron"
                steps:
                  - { name: a, action: http }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("cron");
    }

    @Test
    void rejectsEmptySteps() {
        assertThatThrownBy(() -> parser.parse("""
                name: x
                trigger:
                  cron: "0 0 * * *"
                steps: []
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("steps");
    }

    @Test
    void rejectsDuplicateStepNames() {
        assertThatThrownBy(() -> parser.parse("""
                name: x
                trigger:
                  cron: "0 0 * * *"
                steps:
                  - { name: a, action: http }
                  - { name: a, action: http }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsStepWithoutAction() {
        assertThatThrownBy(() -> parser.parse("""
                name: x
                trigger:
                  cron: "0 0 * * *"
                steps:
                  - { name: a }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("action");
    }

    @Test
    void rejectsWebhookPathWithSlashes() {
        assertThatThrownBy(() -> parser.parse("""
                name: x
                trigger:
                  webhook: { path: "a/b" }
                steps:
                  - { name: a, action: http }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("path");
    }

    @Test
    void rejectsInvalidYaml() {
        assertThatThrownBy(() -> parser.parse("a: [unclosed"))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("YAML");
    }

    @Test
    void normalizesFiveFieldCron() {
        assertThat(YamlDefinitionParser.normalizeCron("0 19 * * *")).isEqualTo("0 0 19 * * *");
        assertThat(YamlDefinitionParser.normalizeCron("0 0 19 * * *")).isEqualTo("0 0 19 * * *");
    }

    @Test
    void withValuesKeepYamlTypes() {
        WorkflowDefinition definition = parser.parse("""
                name: typed
                trigger:
                  webhook: { path: "t" }
                steps:
                  - name: fetch
                    action: http
                    with:
                      timeout: 30
                      verbose: true
                      nested: { a: 1 }
                """);

        Map<String, Object> with = definition.steps().get(0).with();
        assertThat(with.get("timeout")).isEqualTo(30);
        assertThat(with.get("verbose")).isEqualTo(true);
        assertThat(with.get("nested")).isEqualTo(Map.of("a", 1));
    }
}
