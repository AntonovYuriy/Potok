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
    void evaluatesOrderingOperators() {
        assertThat(resolver.evaluateCondition("{{ steps.fetch.status >= 200 }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ steps.fetch.status > 200 }}", context)).isFalse();
        assertThat(resolver.evaluateCondition("{{ steps.fetch.status <= 200 }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ steps.fetch.status < 300 }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ steps.fetch.status < 200 }}", context)).isFalse();
    }

    @Test
    void ordersStringsLexicographically() {
        assertThat(resolver.evaluateCondition("{{ trigger.user > 'x' }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ trigger.user < 'z' }}", context)).isTrue();
    }

    @Test
    void evaluatesContains() {
        assertThat(resolver.evaluateCondition("{{ contains(steps.fetch.body.message, 'o') }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ contains(steps.fetch.body.message, 'zzz') }}", context)).isFalse();
        // list membership
        assertThat(resolver.evaluateCondition("{{ contains(trigger.items, 'a') }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ contains(trigger.items, 'q') }}", context)).isFalse();
        // literal haystack
        assertThat(resolver.evaluateCondition("contains('hello world', 'wor')", context)).isTrue();
    }

    @Test
    void evaluatesExists() {
        assertThat(resolver.evaluateCondition("{{ exists(steps.fetch.status) }}", context)).isTrue();
        assertThat(resolver.evaluateCondition("{{ exists(steps.fetch.nope) }}", context)).isFalse();
        assertThat(resolver.evaluateCondition("{{ exists(trigger.user) }}", context)).isTrue();
    }

    @Test
    void containsWithMissingPathIsFalse() {
        assertThat(resolver.evaluateCondition("{{ contains(steps.nope.body, 'x') }}", context)).isFalse();
    }

    @Test
    void evaluatesAndOr() {
        assertThat(resolver.evaluateCondition(
                "{{ steps.fetch.status == 200 && trigger.user == 'yura' }}", context)).isTrue();
        assertThat(resolver.evaluateCondition(
                "{{ steps.fetch.status == 500 && trigger.user == 'yura' }}", context)).isFalse();
        assertThat(resolver.evaluateCondition(
                "{{ steps.fetch.status == 500 || trigger.user == 'yura' }}", context)).isTrue();
        assertThat(resolver.evaluateCondition(
                "{{ steps.fetch.status == 500 || trigger.user == 'nope' }}", context)).isFalse();
    }

    @Test
    void andBindsTighterThanOr() {
        // false || (true && true) -> true; ((false || true) && true) would also be true,
        // so prove precedence with: true || x && false  => true (|| wins only if && grouped right)
        assertThat(resolver.evaluateCondition(
                "{{ trigger.user == 'yura' || trigger.user == 'x' && steps.fetch.status == 500 }}",
                context)).isTrue();
        // (a || b) && false -> false with explicit parens
        assertThat(resolver.evaluateCondition(
                "{{ (trigger.user == 'yura' || trigger.user == 'x') && steps.fetch.status == 500 }}",
                context)).isFalse();
    }

    @Test
    void parenthesesGroup() {
        assertThat(resolver.evaluateCondition(
                "{{ (steps.fetch.status == 200) }}", context)).isTrue();
        assertThat(resolver.evaluateCondition(
                "{{ ((steps.fetch.status >= 200) && (steps.fetch.status < 300)) || trigger.user == 'x' }}",
                context)).isTrue();
    }

    @Test
    void functionsComposeWithBooleans() {
        assertThat(resolver.evaluateCondition(
                "{{ exists(trigger.user) && contains(trigger.items, 'a') }}", context)).isTrue();
        assertThat(resolver.evaluateCondition(
                "{{ exists(trigger.nope) || contains(steps.fetch.body.message, 'ok') }}", context)).isTrue();
    }

    @Test
    void quotedOperatorsAreNotBooleanSplit() {
        assertThat(resolver.evaluateCondition(
                "{{ trigger.user == 'a && b' }}", context)).isFalse();
        assertThat(resolver.evaluateCondition(
                "{{ trigger.user == 'a || b' || trigger.user == 'yura' }}", context)).isTrue();
    }

    @Test
    void emptyOperandThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        resolver.evaluateCondition("{{ steps.fetch.status == 200 && }}", context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty operand");
    }

    @Test
    void validateConditionSyntaxCatchesMalformedInput() {
        resolver.validateConditionSyntax("{{ steps.x.status == 200 && exists(trigger.a) }}"); // ok
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        resolver.validateConditionSyntax("{{ steps.x.status == 200 && }}"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        resolver.validateConditionSyntax("{{ (steps.x.status == 200 }}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parentheses");
    }

    @Test
    void worksWithoutWrappingBraces() {
        assertThat(resolver.evaluateCondition("steps.fetch.status == 200", context)).isTrue();
    }

    @Test
    void quotedOperatorIsNotSplit() {
        assertThat(resolver.evaluateCondition("{{ trigger.user == 'a==b' }}", context)).isFalse();
    }

    @Test
    void conditionLiteralWithApostropheInsideDoubleQuotes() {
        TemplateResolver resolver = new TemplateResolver(name -> null);
        java.util.Map<String, Object> ctx = java.util.Map.of(
                "poll", java.util.Map.of("value", "Lineup: O'Brien, more"));
        assertThat(resolver.evaluateCondition(
                "{{ contains(poll.value, \"O'Brien\") }}", ctx)).isTrue();
        assertThat(resolver.evaluateCondition(
                "{{ contains(poll.value, \"O'Connor\") }}", ctx)).isFalse();
    }

    @Test
    void conditionLiteralWithEscapedDoubleQuote() {
        TemplateResolver resolver = new TemplateResolver(name -> null);
        java.util.Map<String, Object> ctx = java.util.Map.of(
                "poll", java.util.Map.of("value", "they said \"hi\" loudly"));
        assertThat(resolver.evaluateCondition(
                "{{ contains(poll.value, \"said \\\"hi\\\"\") }}", ctx)).isTrue();
        // escaped quote must not end the literal and hide the && that follows
        assertThat(resolver.evaluateCondition(
                "{{ contains(poll.value, \"\\\"hi\\\"\") && poll.value }}", ctx)).isTrue();
    }
}
