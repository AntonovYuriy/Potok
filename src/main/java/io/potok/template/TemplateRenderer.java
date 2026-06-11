package io.potok.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders workflow templates: replaces {@code {{param.key}}} placeholders with
 * values. Deliberately distinct from the runtime {@code {{ steps.* }}}
 * templating — only the {@code param.} namespace is substituted, everything
 * else passes through untouched.
 *
 * Also a CLI ({@link #main}): regenerates examples/ from templates/ using each
 * manifest entry's defaults — examples are committed, and an integration test
 * fails if they drift from the templates.
 */
public final class TemplateRenderer {

    private static final Pattern PARAM = Pattern.compile("\\{\\{param\\.([A-Za-z0-9_]+)}}");

    private TemplateRenderer() {
    }

    public static String render(String template, Map<String, String> values) {
        Matcher matcher = PARAM.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = values.get(key);
            if (value == null) {
                throw new IllegalArgumentException("missing template parameter '" + key + "'");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Defaults for a manifest entry: every non-env param's default plus the workflow name. */
    public static Map<String, String> defaults(JsonNode entry) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", entry.path("default_name").asText(entry.path("id").asText()));
        for (JsonNode param : entry.path("params")) {
            if ("env".equals(param.path("type").asText())) {
                continue; // env params are UI notes; templates carry the ${VAR} literal
            }
            String key = param.path("key").asText();
            if (param.hasNonNull("default")) {
                values.put(key, param.path("default").asText());
            }
        }
        return values;
    }

    /** CLI: {@code TemplateRenderer <repoRoot>} — regenerate examples/ from templates/. */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(
                Files.readString(root.resolve("src/main/resources/static/help/templates.json")));
        for (JsonNode entry : manifest) {
            String id = entry.path("id").asText();
            Path tpl = root.resolve("templates").resolve(id + ".yaml.tpl");
            Path out = root.resolve("examples").resolve(entry.path("file").asText());
            Files.writeString(out, render(Files.readString(tpl), defaults(entry)));
            System.out.println("rendered " + out);
        }
    }
}
