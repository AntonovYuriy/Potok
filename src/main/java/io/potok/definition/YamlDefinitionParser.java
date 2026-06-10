package io.potok.definition;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates YAML workflow definitions into {@link WorkflowDefinition}.
 */
@Component
public class YamlDefinitionParser {

    public WorkflowDefinition parse(String yamlSource) {
        if (yamlSource == null || yamlSource.isBlank()) {
            throw new InvalidDefinitionException("definition is empty");
        }
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlSource);
        } catch (YAMLException e) {
            throw new InvalidDefinitionException("invalid YAML: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new InvalidDefinitionException("definition must be a YAML mapping");
        }

        String name = stringField(map, "name");
        if (name == null || name.isBlank()) {
            throw new InvalidDefinitionException("'name' is required");
        }

        WorkflowDefinition.Trigger trigger = parseTrigger(map.get("trigger"));
        List<WorkflowDefinition.Step> steps = parseSteps(map.get("steps"));

        return new WorkflowDefinition(name, trigger, steps);
    }

    private WorkflowDefinition.Trigger parseTrigger(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidDefinitionException("'trigger' is required and must be a mapping");
        }
        Object cron = map.get("cron");
        Object webhook = map.get("webhook");
        if ((cron == null) == (webhook == null)) {
            throw new InvalidDefinitionException("'trigger' must define exactly one of 'cron' or 'webhook'");
        }
        if (cron != null) {
            String expr = cron.toString().trim();
            try {
                CronExpression.parse(normalizeCron(expr));
            } catch (IllegalArgumentException e) {
                throw new InvalidDefinitionException("invalid cron expression '" + expr + "': " + e.getMessage(), e);
            }
            return new WorkflowDefinition.Trigger(expr, null);
        }
        if (!(webhook instanceof Map<?, ?> webhookMap)) {
            throw new InvalidDefinitionException("'trigger.webhook' must be a mapping with a 'path'");
        }
        String path = stringField(webhookMap, "path");
        if (path == null || path.isBlank()) {
            throw new InvalidDefinitionException("'trigger.webhook.path' is required");
        }
        if (!path.matches("[a-zA-Z0-9_-]+")) {
            throw new InvalidDefinitionException(
                    "'trigger.webhook.path' may only contain letters, digits, '-' and '_'");
        }
        return new WorkflowDefinition.Trigger(null, new WorkflowDefinition.Webhook(path));
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowDefinition.Step> parseSteps(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new InvalidDefinitionException("'steps' must be a non-empty list");
        }
        List<WorkflowDefinition.Step> steps = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> stepMap)) {
                throw new InvalidDefinitionException("steps[" + i + "] must be a mapping");
            }
            String name = stringField(stepMap, "name");
            if (name == null || name.isBlank()) {
                throw new InvalidDefinitionException("steps[" + i + "]: 'name' is required");
            }
            if (!names.add(name)) {
                throw new InvalidDefinitionException("duplicate step name '" + name + "'");
            }
            String action = stringField(stepMap, "action");
            if (action == null || action.isBlank()) {
                throw new InvalidDefinitionException("step '" + name + "': 'action' is required");
            }
            Object with = stepMap.get("with");
            if (with != null && !(with instanceof Map)) {
                throw new InvalidDefinitionException("step '" + name + "': 'with' must be a mapping");
            }
            Object condition = stepMap.get("if");
            Integer maxAttemptsValue = positiveInt(name, "max_attempts", stepMap.get("max_attempts"));
            steps.add(new WorkflowDefinition.Step(
                    name,
                    action,
                    condition == null ? null : condition.toString(),
                    with == null ? null : (Map<String, Object>) with,
                    maxAttemptsValue,
                    parseRetry(name, stepMap.get("retry"))));
        }
        return steps;
    }

    private WorkflowDefinition.Retry parseRetry(String stepName, Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidDefinitionException("step '" + stepName + "': 'retry' must be a mapping");
        }
        return new WorkflowDefinition.Retry(
                positiveInt(stepName, "retry.max_attempts", map.get("max_attempts")),
                parseDuration(stepName, "retry.base_delay", map.get("base_delay")),
                parseDuration(stepName, "retry.max_delay", map.get("max_delay")));
    }

    private static Integer positiveInt(String stepName, String field, Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Integer intValue) || intValue < 1) {
            throw new InvalidDefinitionException(
                    "step '" + stepName + "': '" + field + "' must be a positive integer");
        }
        return intValue;
    }

    /** Accepts "500ms", "10s", "5m", "2h", a plain integer (seconds), or ISO-8601 ("PT10S"). */
    static java.time.Duration parseDuration(String stepName, String field, Object raw) {
        if (raw == null) {
            return null;
        }
        java.time.Duration parsed = null;
        if (raw instanceof Integer seconds && seconds > 0) {
            parsed = java.time.Duration.ofSeconds(seconds);
        } else {
            String text = raw.toString().trim();
            var matcher = java.util.regex.Pattern.compile("(\\d+)(ms|s|m|h)").matcher(text);
            if (matcher.matches()) {
                long amount = Long.parseLong(matcher.group(1));
                parsed = switch (matcher.group(2)) {
                    case "ms" -> java.time.Duration.ofMillis(amount);
                    case "s" -> java.time.Duration.ofSeconds(amount);
                    case "m" -> java.time.Duration.ofMinutes(amount);
                    default -> java.time.Duration.ofHours(amount);
                };
            } else {
                try {
                    parsed = java.time.Duration.parse(text);
                } catch (java.time.format.DateTimeParseException ignored) {
                    // handled below
                }
            }
        }
        if (parsed == null || parsed.isNegative() || parsed.isZero()) {
            throw new InvalidDefinitionException("step '" + stepName + "': '" + field
                    + "' must be a positive duration like \"10s\", \"5m\" or \"PT10S\"");
        }
        return parsed;
    }

    /** Spring cron needs 6 fields (with seconds); classic 5-field crontab specs get a leading "0". */
    public static String normalizeCron(String cron) {
        String trimmed = cron.trim();
        return trimmed.split("\\s+").length == 5 ? "0 " + trimmed : trimmed;
    }

    private static String stringField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }
}
