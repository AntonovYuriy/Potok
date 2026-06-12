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

    private final TemplateResolver conditionValidator;

    public YamlDefinitionParser() {
        this(new TemplateResolver());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public YamlDefinitionParser(TemplateResolver conditionValidator) {
        this.conditionValidator = conditionValidator;
    }

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

        WorkflowDefinition definition = new WorkflowDefinition(name, trigger, steps);
        validateNeeds(definition);
        validateTemplateReferences(definition);
        validateConditions(definition);
        return definition;
    }

    /** Malformed conditions fail at create/update, not at execution time. */
    private void validateConditions(WorkflowDefinition definition) {
        for (WorkflowDefinition.Step step : definition.steps()) {
            if (step.condition() != null) {
                try {
                    conditionValidator.validateConditionSyntax(step.condition());
                } catch (IllegalArgumentException e) {
                    throw new InvalidDefinitionException(
                            "step '" + step.name() + "': invalid condition: " + e.getMessage());
                }
            }
        }
        WorkflowDefinition.Poll poll = definition.trigger().poll();
        if (poll != null && !"changed".equals(poll.fireWhen())) {
            try {
                conditionValidator.validateConditionSyntax(poll.fireWhen());
            } catch (IllegalArgumentException e) {
                throw new InvalidDefinitionException(
                        "'trigger.poll.fire_when': invalid condition: " + e.getMessage());
            }
        }
    }

    private void validateNeeds(WorkflowDefinition definition) {
        Set<String> names = new HashSet<>();
        definition.steps().forEach(s -> names.add(s.name()));
        for (WorkflowDefinition.Step step : definition.steps()) {
            if (step.needs() == null) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (String need : step.needs()) {
                if (!names.contains(need)) {
                    throw new InvalidDefinitionException(
                            "step '" + step.name() + "': needs unknown step '" + need + "'");
                }
                if (need.equals(step.name())) {
                    throw new InvalidDefinitionException(
                            "step '" + step.name() + "': cannot depend on itself");
                }
                if (!seen.add(need)) {
                    throw new InvalidDefinitionException(
                            "step '" + step.name() + "': duplicate entry '" + need + "' in needs");
                }
            }
        }
        detectCycles(definition);
    }

    /** DFS with three colors; reports the offending path. */
    private void detectCycles(WorkflowDefinition definition) {
        Map<String, Integer> color = new java.util.HashMap<>(); // 0/absent=white, 1=gray, 2=black
        for (WorkflowDefinition.Step step : definition.steps()) {
            if (!color.containsKey(step.name())) {
                dfsCycle(definition, step.name(), color, new java.util.ArrayDeque<>());
            }
        }
    }

    private void dfsCycle(WorkflowDefinition definition, String node,
                          Map<String, Integer> color, java.util.Deque<String> path) {
        color.put(node, 1);
        path.push(node);
        for (String need : definition.effectiveNeeds(node)) {
            Integer c = color.get(need);
            if (c != null && c == 1) {
                List<String> cycle = new java.util.ArrayList<>(path);
                java.util.Collections.reverse(cycle);
                cycle.add(need);
                throw new InvalidDefinitionException(
                        "dependency cycle detected: " + String.join(" -> ", cycle));
            }
            if (c == null) {
                dfsCycle(definition, need, color, path);
            }
        }
        path.pop();
        color.put(node, 2);
    }

    private static final java.util.regex.Pattern STEP_REF =
            java.util.regex.Pattern.compile("steps\\.([A-Za-z0-9_-]+)");

    /** A step may only reference outputs of steps it (transitively) depends on. */
    private void validateTemplateReferences(WorkflowDefinition definition) {
        for (WorkflowDefinition.Step step : definition.steps()) {
            Set<String> allowed = definition.needsClosure(step.name());
            Set<String> referenced = new java.util.LinkedHashSet<>();
            if (step.condition() != null) {
                collectStepRefs(step.condition(), referenced);
            }
            collectStepRefsDeep(step.with(), referenced);
            for (String ref : referenced) {
                if (!allowed.contains(ref)) {
                    throw new InvalidDefinitionException(
                            "step '" + step.name() + "': references steps." + ref
                                    + " but does not depend on it — add '" + ref + "' to needs");
                }
            }
        }
    }

    private void collectStepRefsDeep(Object value, Set<String> out) {
        if (value instanceof String s) {
            collectStepRefs(s, out);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectStepRefsDeep(v, out));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> collectStepRefsDeep(v, out));
        }
    }

    private void collectStepRefs(String text, Set<String> out) {
        var matcher = STEP_REF.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
    }

    private WorkflowDefinition.Trigger parseTrigger(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidDefinitionException("'trigger' is required and must be a mapping");
        }
        Object cron = map.get("cron");
        Object webhook = map.get("webhook");
        Object poll = map.get("poll");
        Object rss = map.get("rss");
        long defined = java.util.stream.Stream.of(cron, webhook, poll, rss).filter(java.util.Objects::nonNull).count();
        if (defined != 1) {
            throw new InvalidDefinitionException(
                    "'trigger' must define exactly one of 'cron', 'webhook', 'poll' or 'rss'");
        }
        if (cron != null) {
            String expr = cron.toString().trim();
            try {
                CronExpression.parse(normalizeCron(expr));
            } catch (IllegalArgumentException e) {
                throw new InvalidDefinitionException("invalid cron expression '" + expr + "': " + e.getMessage(), e);
            }
            return new WorkflowDefinition.Trigger(expr, null, null, null);
        }
        if (webhook != null) {
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
            String hmacSecretEnv = stringField(webhookMap, "hmac_secret_env");
            if (hmacSecretEnv != null && !hmacSecretEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new InvalidDefinitionException(
                        "'trigger.webhook.hmac_secret_env' must be an environment variable NAME");
            }
            return new WorkflowDefinition.Trigger(null,
                    new WorkflowDefinition.Webhook(path, hmacSecretEnv), null, null);
        }
        if (poll != null) {
            return new WorkflowDefinition.Trigger(null, null, parsePoll(poll), null);
        }
        return new WorkflowDefinition.Trigger(null, null, null, parseRss(rss));
    }

    @SuppressWarnings("unchecked")
    private WorkflowDefinition.Poll parsePoll(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidDefinitionException("'trigger.poll' must be a mapping");
        }
        java.time.Duration interval = parseDuration("trigger", "poll.interval", map.get("interval"));
        if (interval == null) {
            throw new InvalidDefinitionException("'trigger.poll.interval' is required");
        }
        if (!(map.get("http") instanceof Map<?, ?> http) || stringField(http, "url") == null) {
            throw new InvalidDefinitionException("'trigger.poll.http' must be a mapping with a 'url'");
        }
        String fireWhen = stringField(map, "fire_when");
        if (fireWhen == null || fireWhen.isBlank()) {
            throw new InvalidDefinitionException(
                    "'trigger.poll.fire_when' is required: \"changed\" or a condition expression");
        }
        return new WorkflowDefinition.Poll(interval, (Map<String, Object>) http, fireWhen.trim(),
                parseExtract(map.get("extract")));
    }

    private WorkflowDefinition.Extract parseExtract(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidDefinitionException("'trigger.poll.extract' must be a mapping");
        }
        String jsonpath = stringField(map, "jsonpath");
        String css = stringField(map, "css");
        if ((jsonpath == null) == (css == null)) {
            throw new InvalidDefinitionException(
                    "'trigger.poll.extract' must define exactly one of 'jsonpath' or 'css'");
        }
        Object number = map.get("number");
        if (number != null && !(number instanceof Boolean)) {
            throw new InvalidDefinitionException("'trigger.poll.extract.number' must be true or false");
        }
        return new WorkflowDefinition.Extract(jsonpath, css, (Boolean) number);
    }

    private WorkflowDefinition.Rss parseRss(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidDefinitionException("'trigger.rss' must be a mapping");
        }
        java.time.Duration interval = parseDuration("trigger", "rss.interval", map.get("interval"));
        if (interval == null) {
            throw new InvalidDefinitionException("'trigger.rss.interval' is required");
        }
        String url = stringField(map, "url");
        if (url == null || url.isBlank()) {
            throw new InvalidDefinitionException("'trigger.rss.url' is required");
        }
        return new WorkflowDefinition.Rss(interval, url);
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
            java.time.Duration wait = parseDuration(name, "wait", stepMap.get("wait"));
            boolean hasAction = action != null && !action.isBlank();
            if (hasAction == (wait != null)) {
                throw new InvalidDefinitionException(wait != null
                        ? "step '" + name + "': 'wait' and 'action' are mutually exclusive"
                        : "step '" + name + "': either 'action' or 'wait' is required");
            }
            Object with = stepMap.get("with");
            if (with != null && !(with instanceof Map)) {
                throw new InvalidDefinitionException("step '" + name + "': 'with' must be a mapping");
            }
            if (wait != null && with != null) {
                throw new InvalidDefinitionException(
                        "step '" + name + "': a 'wait' step takes no 'with' inputs");
            }
            if ("approval".equals(action)) {
                validateApproval(name, with);
            }
            Object condition = stepMap.get("if");
            Integer maxAttemptsValue = positiveInt(name, "max_attempts", stepMap.get("max_attempts"));
            steps.add(new WorkflowDefinition.Step(
                    name,
                    hasAction ? action : null,
                    condition == null ? null : condition.toString(),
                    with == null ? null : (Map<String, Object>) with,
                    maxAttemptsValue,
                    parseRetry(name, stepMap.get("retry")),
                    parseNeeds(name, stepMap.get("needs")),
                    wait));
        }
        return steps;
    }

    /**
     * approval defaults (backward-compat rule: minimal config works):
     * only 'text' is required; timeout absent -> 24h, channel absent -> telegram.
     */
    private void validateApproval(String stepName, Object with) {
        Map<?, ?> map = with instanceof Map<?, ?> m ? m : Map.of();
        Object text = map.get("text");
        if (text == null || text.toString().isBlank()) {
            throw new InvalidDefinitionException(
                    "step '" + stepName + "': approval requires 'with.text' (the question to ask)");
        }
        Object timeout = map.get("timeout");
        if (timeout != null && !timeout.toString().contains("{{")) {
            parseDuration(stepName, "timeout", timeout); // templated values are validated at run time
        }
        Object channel = map.get("channel");
        if (channel != null && !"telegram".equals(channel.toString())) {
            throw new InvalidDefinitionException("step '" + stepName
                    + "': approval channel '" + channel + "' is not supported (only: telegram)");
        }
    }

    private List<String> parseNeeds(String stepName, Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String single) {
            return List.of(single);
        }
        if (!(raw instanceof List<?> list)) {
            throw new InvalidDefinitionException(
                    "step '" + stepName + "': 'needs' must be a list of step names");
        }
        List<String> needs = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new InvalidDefinitionException(
                        "step '" + stepName + "': 'needs' entries must be step names");
            }
            needs.add(s);
        }
        return needs;
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

    /** Accepts "500ms", "10s", "5m", "2h", "3d", a plain integer (seconds), or ISO-8601 ("PT10S"). */
    public static java.time.Duration parseDuration(String stepName, String field, Object raw) {
        if (raw == null) {
            return null;
        }
        java.time.Duration parsed = null;
        if (raw instanceof Integer seconds && seconds > 0) {
            parsed = java.time.Duration.ofSeconds(seconds);
        } else {
            String text = raw.toString().trim();
            var matcher = java.util.regex.Pattern.compile("(\\d+)(ms|s|m|h|d)").matcher(text);
            if (matcher.matches()) {
                long amount = Long.parseLong(matcher.group(1));
                parsed = switch (matcher.group(2)) {
                    case "ms" -> java.time.Duration.ofMillis(amount);
                    case "s" -> java.time.Duration.ofSeconds(amount);
                    case "m" -> java.time.Duration.ofMinutes(amount);
                    case "d" -> java.time.Duration.ofDays(amount);
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
