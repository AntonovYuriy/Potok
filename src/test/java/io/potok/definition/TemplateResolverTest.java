package io.potok.definition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateResolverTest {

    private final TemplateResolver resolver =
            new TemplateResolver(Map.of("TELEGRAM_CHAT_ID", "42", "TOKEN", "secret")::get);

    private final Map<String, Object> context = Map.of(
            "trigger", Map.of("user", "yura", "items", List.of("a", "b")),
            "steps", Map.of("fetch", Map.of(
                    "status", 200,
                    "body", Map.of("message", "ok"))));

    @Test
    void resolvesDotPathInText() {
        assertThat(resolver.resolveString("status={{ steps.fetch.status }}", context))
                .isEqualTo("status=200");
    }

    @Test
    void resolvesNestedPath() {
        assertThat(resolver.resolveString("{{ steps.fetch.body.message }} and {{ trigger.user }}", context))
                .isEqualTo("ok and yura");
    }

    @Test
    void resolvesListIndex() {
        assertThat(resolver.resolveString("{{ trigger.items.1 }}", context)).isEqualTo("b");
    }

    @Test
    void unknownPathRendersEmpty() {
        assertThat(resolver.resolveString("[{{ steps.nope.x }}]", context)).isEqualTo("[]");
    }

    @Test
    void singleExpressionPreservesType() {
        Object resolved = resolver.resolve("{{ steps.fetch.status }}", context);
        assertThat(resolved).isEqualTo(200);

        Object body = resolver.resolve("{{ steps.fetch.body }}", context);
        assertThat(body).isEqualTo(Map.of("message", "ok"));
    }

    @Test
    void resolvesMapsAndListsRecursively() {
        Object resolved = resolver.resolve(
                Map.of("text", "code {{ steps.fetch.status }}", "list", List.of("{{ trigger.user }}")),
                context);

        assertThat(resolved).isEqualTo(Map.of("text", "code 200", "list", List.of("yura")));
    }

    @Test
    void substitutesEnvVars() {
        assertThat(resolver.resolveString("chat=${TELEGRAM_CHAT_ID}", context)).isEqualTo("chat=42");
    }

    @Test
    void missingEnvVarRendersEmpty() {
        assertThat(resolver.resolveString("x=${MISSING_VAR}!", context)).isEqualTo("x=!");
    }

    @Test
    void evaluatesNumericEquality() {
        assertThat(resolver.evaluateCondition("{{ steps.fetch.status == 200 }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ steps.fetch.status == 500 }}", context)).isFalse();
    }

    @Test
    void evaluatesInequality() {
        assertThat(resolver.evaluateCondition("{{ steps.fetch.status != 500 }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ steps.fetch.status != 200 }}", context)).isFalse();
    }

    @Test
    void evaluatesStringComparison() {
        assertThat(resolver.evaluateCondition("{{ trigger.user == 'yura' }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ trigger.user == \"other\" }}", context)).isFalse();
    }

    @Test
    void evaluatesBarePathTruthiness() {
        assertThat(resolver.evaluateCondition("{{ trigger.user }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ trigger.missing }}", context)).isFalse();
    }

    @Test
    void evaluatesBooleanLiterals() {
        assertThat(resolver.evaluateCondition("{{ true }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ false }}", context)).isFalse();
    }

    @Test
    void comparesAgainstNull() {
        assertThat(resolver.evaluateCondition("{{ trigger.missing == null }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ trigger.user == null }}", context)).isFalse();
    }

    @Test
    void worksWithoutWrappingBraces() {
        assertThat(resolver.evaluateCondition("steps.fetch.status == 200", context)).isTrue();
    }

    @Test
    void quotedOperatorIsNotSplit() {
        assertThat(resolver.evaluateCondition("{{ trigger.user == 'a==b' }}", context)).isFalse();
    }
}
