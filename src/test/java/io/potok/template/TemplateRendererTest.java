package io.potok.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRendererTest {

    @Test
    void substitutesParamPlaceholders() {
        String rendered = TemplateRenderer.render(
                "name: {{param.name}}\nurl: \"{{param.url}}\"",
                Map.of("name", "wf", "url", "https://x"));
        assertThat(rendered).isEqualTo("name: wf\nurl: \"https://x\"");
    }

    @Test
    void leavesRuntimeTemplatingUntouched() {
        String tpl = "if: \"{{ steps.probe.status != 200 }}\"\ntext: \"{{param.msg}} {{ trigger.value }}\"";
        String rendered = TemplateRenderer.render(tpl, Map.of("msg", "hi"));
        assertThat(rendered).contains("{{ steps.probe.status != 200 }}");
        assertThat(rendered).contains("hi {{ trigger.value }}");
    }

    @Test
    void substitutesNestedInsideRuntimeExpression() {
        String rendered = TemplateRenderer.render(
                "fire_when: \"{{ poll.value < {{param.threshold}} }}\"",
                Map.of("threshold", "4.20"));
        assertThat(rendered).isEqualTo("fire_when: \"{{ poll.value < 4.20 }}\"");
    }

    @Test
    void condFilterEscapesQuotesForConditionInsideSingleQuotedYaml() {
        String tpl = "fire_when: '{{ contains(poll.value, \"{{param.keyword|cond}}\") }}'";
        assertThat(TemplateRenderer.render(tpl, java.util.Map.of("keyword", "O'Brien")))
                .isEqualTo("fire_when: '{{ contains(poll.value, \"O''Brien\") }}'");
        assertThat(TemplateRenderer.render(tpl, java.util.Map.of("keyword", "say \"hi\"")))
                .isEqualTo("fire_when: '{{ contains(poll.value, \"say \\\"hi\\\"\") }}'");
    }

    @Test
    void dqFilterEscapesForDoubleQuotedYaml() {
        String tpl = "text: \"x {{param.keyword|dq}} y\"";
        assertThat(TemplateRenderer.render(tpl, java.util.Map.of("keyword", "say \"hi\"")))
                .isEqualTo("text: \"x say \\\"hi\\\" y\"");
        assertThat(TemplateRenderer.render(tpl, java.util.Map.of("keyword", "O'Brien")))
                .isEqualTo("text: \"x O'Brien y\"");
        assertThat(TemplateRenderer.escape("a\\b", "dq")).isEqualTo("a\\\\b");
    }

    @Test
    void missingParamThrows() {
        assertThatThrownBy(() -> TemplateRenderer.render("x: {{param.absent}}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent");
    }

    @Test
    void defaultsSkipEnvParamsAndIncludeName() throws Exception {
        var entry = new ObjectMapper().readTree("""
                {"id": "t", "default_name": "my-wf", "params": [
                  {"key": "url", "type": "url", "default": "https://x"},
                  {"key": "chat", "type": "env", "env": "TELEGRAM_CHAT_ID"}
                ]}""");
        Map<String, String> defaults = TemplateRenderer.defaults(entry);
        assertThat(defaults).containsEntry("name", "my-wf").containsEntry("url", "https://x");
        assertThat(defaults).doesNotContainKey("chat");
    }
}
