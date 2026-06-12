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
    void parsesRetryBlock() {
        WorkflowDefinition definition = parser.parse("""
                name: retry-block
                trigger:
                  webhook: { path: "r" }
                steps:
                  - name: fetch
                    action: http
                    retry:
                      max_attempts: 7
                      base_delay: 5s
                      max_delay: 2m
                """);

        WorkflowDefinition.Retry retry = definition.steps().get(0).retry();
        assertThat(retry.maxAttempts()).isEqualTo(7);
        assertThat(retry.baseDelay()).isEqualTo(java.time.Duration.ofSeconds(5));
        assertThat(retry.maxDelay()).isEqualTo(java.time.Duration.ofMinutes(2));
        assertThat(definition.steps().get(0).effectiveMaxAttempts()).isEqualTo(7);
    }

    @Test
    void parsesDurationFormats() {
        assertThat(YamlDefinitionParser.parseDuration("s", "f", "500ms"))
                .isEqualTo(java.time.Duration.ofMillis(500));
        assertThat(YamlDefinitionParser.parseDuration("s", "f", 30))
                .isEqualTo(java.time.Duration.ofSeconds(30));
        assertThat(YamlDefinitionParser.parseDuration("s", "f", "2h"))
                .isEqualTo(java.time.Duration.ofHours(2));
        assertThat(YamlDefinitionParser.parseDuration("s", "f", "PT10S"))
                .isEqualTo(java.time.Duration.ofSeconds(10));
    }

    @Test
    void rejectsMalformedStepCondition() {
        assertThatThrownBy(() -> parser.parse("""
                name: x
                trigger:
                  webhook: { path: "x" }
                steps:
                  - { name: f, action: http }
                  - { name: g, action: http, needs: [f], if: "{{ steps.f.status == 200 && }}" }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("step 'g'")
                .hasMessageContaining("invalid condition");
    }

    @Test
    void rejectsMalformedFireWhen() {
        assertThatThrownBy(() -> parser.parse("""
                name: x
                trigger:
                  poll:
                    interval: 5m
                    http: { url: "https://x" }
                    fire_when: "{{ (body.price < 100 }}"
                steps:
                  - { name: f, action: http }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("fire_when");
    }

    @Test
    void rejectsInvalidRetryDelay() {
        assertThatThrownBy(() -> parser.parse("""
                name: x
                trigger:
                  webhook: { path: "x" }
                steps:
                  - name: a
                    action: http
                    retry: { base_delay: "soon" }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("base_delay");
    }

    @Test
    void parsesNeedsAndComputesEffectiveDependencies() {
        WorkflowDefinition d = parser.parse("""
                name: diamond
                trigger:
                  webhook: { path: "d" }
                steps:
                  - { name: a, action: http }
                  - { name: b, action: http, needs: [a] }
                  - { name: c, action: http, needs: [a] }
                  - { name: d, action: http, needs: [b, c] }
                """);

        assertThat(d.effectiveNeeds("a")).isEmpty();
        assertThat(d.effectiveNeeds("d")).containsExactly("b", "c");
        assertThat(d.rootSteps()).extracting(WorkflowDefinition.Step::name).containsExactly("a");
        assertThat(d.needsClosure("d")).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(d.dependents("a")).extracting(WorkflowDefinition.Step::name).containsExactly("b", "c");
    }

    @Test
    void linearWorkflowGetsImplicitNeeds() {
        WorkflowDefinition d = parser.parse("""
                name: linear
                trigger:
                  webhook: { path: "l" }
                steps:
                  - { name: a, action: http }
                  - { name: b, action: http }
                """);

        assertThat(d.effectiveNeeds("b")).containsExactly("a");
        assertThat(d.rootSteps()).extracting(WorkflowDefinition.Step::name).containsExactly("a");
    }

    @Test
    void rejectsNeedsCycle() {
        assertThatThrownBy(() -> parser.parse("""
                name: cyclic
                trigger:
                  webhook: { path: "c" }
                steps:
                  - { name: a, action: http, needs: [c] }
                  - { name: b, action: http, needs: [a] }
                  - { name: c, action: http, needs: [b] }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void rejectsUnknownNeed() {
        assertThatThrownBy(() -> parser.parse("""
                name: x
                trigger:
                  webhook: { path: "x" }
                steps:
                  - { name: a, action: http, needs: [ghost] }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void rejectsTemplateRefToNonDependency() {
        assertThatThrownBy(() -> parser.parse("""
                name: x
                trigger:
                  webhook: { path: "x" }
                steps:
                  - { name: a, action: http }
                  - { name: b, action: http, needs: [a] }
                  - name: c
                    action: telegram
                    needs: [a]
                    with: { chat_id: "1", text: "{{ steps.b.status }}" }
                """))
                .isInstanceOf(InvalidDefinitionException.class)
                .hasMessageContaining("steps.b");
    }

    @Test
    void allowsTemplateRefToTransitiveDependency() {
        WorkflowDefinition d = parser.parse("""
                name: ok
                trigger:
                  webhook: { path: "ok" }
                steps:
                  - { name: a, action: http }
                  - { name: b, action: http, needs: [a] }
                  - name: c
                    action: telegram
                    needs: [b]
                    with: { chat_id: "1", text: "{{ steps.a.status }}" }
                """);
        assertThat(d.step("c")).isNotNull();
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
        assertThat(definition.downstreamClosure("a")).containsExactly("b");
        assertThat(definition.downstreamClosure("b")).isEmpty();
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

    @Test
    void waitStepParsesAndExcludesAction() {
        var definition = parser.parse("""
                name: w
                trigger:
                  cron: "0 9 * * *"
                steps:
                  - name: first
                    action: http
                    with: { url: "https://example.com" }
                  - name: pause
                    wait: 3d
                  - name: second
                    action: http
                    with: { url: "https://example.com" }
                """);
        assertThat(definition.step("pause").waitFor()).isEqualTo(java.time.Duration.ofDays(3));
        assertThat(definition.step("pause").action()).isNull();

        assertThatThrownBy(() -> parser.parse("""
                name: w
                trigger:
                  cron: "0 9 * * *"
                steps:
                  - name: bad
                    action: http
                    wait: 5m
                    with: { url: "https://example.com" }
                """)).hasMessageContaining("mutually exclusive");

        assertThatThrownBy(() -> parser.parse("""
                name: w
                trigger:
                  cron: "0 9 * * *"
                steps:
                  - name: bad
                    wait: 5m
                    with: { url: "https://example.com" }
                """)).hasMessageContaining("no 'with'");

        assertThatThrownBy(() -> parser.parse("""
                name: w
                trigger:
                  cron: "0 9 * * *"
                steps:
                  - name: bad
                """)).hasMessageContaining("either 'action' or 'wait'");
    }

    @Test
    void approvalValidationAndDefaults() {
        // backward-compat rule: minimal config — only text is required
        var minimal = parser.parse("""
                name: a
                trigger:
                  webhook: { path: "x" }
                steps:
                  - name: ask
                    action: approval
                    with: { text: "Deploy?" }
                """);
        assertThat(minimal.step("ask").action()).isEqualTo("approval");

        assertThatThrownBy(() -> parser.parse("""
                name: a
                trigger:
                  webhook: { path: "x" }
                steps:
                  - name: ask
                    action: approval
                """)).hasMessageContaining("with.text");

        assertThatThrownBy(() -> parser.parse("""
                name: a
                trigger:
                  webhook: { path: "x" }
                steps:
                  - name: ask
                    action: approval
                    with: { text: "Deploy?", channel: "smoke-signals" }
                """)).hasMessageContaining("only: telegram");

        assertThatThrownBy(() -> parser.parse("""
                name: a
                trigger:
                  webhook: { path: "x" }
                steps:
                  - name: ask
                    action: approval
                    with: { text: "Deploy?", timeout: "yes please" }
                """)).hasMessageContaining("duration");
    }
}
