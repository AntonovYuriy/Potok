package io.potok.definition;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal template engine for workflow definitions.
 * Supports:
 * - {@code {{ dot.path }}} lookups over the execution context (trigger payload, step outputs)
 * - {@code {{ left == right }}} / {@code {{ left != right }}} comparisons for step conditions
 * - {@code ${ENV_VAR}} environment variable substitution
 * No full expression language by design (M1 scope).
 */
@Component
public class TemplateResolver {

    private static final Pattern EXPRESSION = Pattern.compile("\\{\\{(.+?)}}");
    // Used with matches(): [^{}] keeps "{{ a }} x {{ b }}" from being mistaken for one expression.
    private static final Pattern SINGLE_EXPRESSION = Pattern.compile("\\{\\{([^{}]+)}}");
    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private final UnaryOperator<String> env;

    public TemplateResolver() {
        this(System::getenv);
    }

    public TemplateResolver(UnaryOperator<String> env) {
        this.env = env;
    }

    /**
     * Resolves templates in an arbitrary YAML value: strings are interpolated,
     * maps and lists are resolved recursively, other types pass through.
     * A string that is exactly one expression (e.g. {@code "{{ steps.fetch.body }}"})
     * resolves to the raw value, preserving its type.
     */
    public Object resolve(Object value, Map<String, Object> context) {
        if (value instanceof String s) {
            return resolveStringValue(s, context);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(String.valueOf(k), resolve(v, context)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> resolve(item, context)).toList();
        }
        return value;
    }

    public String resolveString(String template, Map<String, Object> context) {
        Object resolved = resolveStringValue(template, context);
        return resolved == null ? "" : String.valueOf(resolved);
    }

    private static final String[] OPERATORS = {"==", "!=", ">=", "<=", ">", "<"};

    public boolean evaluateCondition(String expression, Map<String, Object> context) {
        String expr = expression.trim();
        Matcher wrapped = SINGLE_EXPRESSION.matcher(expr);
        if (wrapped.matches()) {
            expr = wrapped.group(1).trim();
        }
        if (expr.startsWith("contains(") && expr.endsWith(")")) {
            return evaluateContains(expr.substring("contains(".length(), expr.length() - 1), context);
        }
        if (expr.startsWith("exists(") && expr.endsWith(")")) {
            return lookup(expr.substring("exists(".length(), expr.length() - 1).trim(), context) != null;
        }
        for (String op : OPERATORS) {
            int at = indexOfOperator(expr, op);
            if (at >= 0) {
                Object left = operand(expr.substring(0, at), context);
                Object right = operand(expr.substring(at + op.length()), context);
                return switch (op) {
                    case "==" -> valuesEqual(left, right);
                    case "!=" -> !valuesEqual(left, right);
                    case ">=" -> compare(left, right) >= 0;
                    case "<=" -> compare(left, right) <= 0;
                    case ">" -> compare(left, right) > 0;
                    default -> compare(left, right) < 0;
                };
            }
        }
        return truthy(operand(expr, context));
    }

    /** contains(haystack, needle): substring for strings, membership for lists. */
    private boolean evaluateContains(String args, Map<String, Object> context) {
        int comma = indexOfOperator(args, ",");
        if (comma < 0) {
            return false;
        }
        Object haystack = operand(args.substring(0, comma), context);
        Object needle = operand(args.substring(comma + 1), context);
        if (haystack instanceof List<?> list) {
            return list.stream().anyMatch(item -> valuesEqual(item, needle));
        }
        if (haystack == null || needle == null) {
            return false;
        }
        return String.valueOf(haystack).contains(String.valueOf(needle));
    }

    /** Numeric when both sides are numbers, lexicographic otherwise; null sorts lowest. */
    private static int compare(Object left, Object right) {
        BigDecimal leftNumber = toNumber(left);
        BigDecimal rightNumber = toNumber(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber);
        }
        if (left == null || right == null) {
            return left == null && right == null ? 0 : (left == null ? -1 : 1);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private Object resolveStringValue(String template, Map<String, Object> context) {
        String withEnv = substituteEnv(template);
        Matcher single = SINGLE_EXPRESSION.matcher(withEnv.trim());
        if (single.matches()) {
            return lookup(single.group(1).trim(), context);
        }
        Matcher matcher = EXPRESSION.matcher(withEnv);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = lookup(matcher.group(1).trim(), context);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String substituteEnv(String template) {
        Matcher matcher = ENV_VAR.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = env.apply(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Object operand(String raw, Map<String, Object> context) {
        String token = raw.trim();
        if (token.length() >= 2
                && (token.charAt(0) == '\'' || token.charAt(0) == '"')
                && token.charAt(token.length() - 1) == token.charAt(0)) {
            return token.substring(1, token.length() - 1);
        }
        if (NUMBER.matcher(token).matches()) {
            return new BigDecimal(token);
        }
        switch (token) {
            case "true" -> {
                return Boolean.TRUE;
            }
            case "false" -> {
                return Boolean.FALSE;
            }
            case "null" -> {
                return null;
            }
            default -> {
                return lookup(token, context);
            }
        }
    }

    /** Walks a dot-path ({@code steps.fetch.status}) through nested maps and lists. */
    private Object lookup(String path, Map<String, Object> context) {
        Object current = context;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list && segment.matches("\\d+")) {
                int index = Integer.parseInt(segment);
                current = index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number || right instanceof Number) {
            BigDecimal leftNumber = toNumber(left);
            BigDecimal rightNumber = toNumber(right);
            if (leftNumber != null && rightNumber != null) {
                return leftNumber.compareTo(rightNumber) == 0;
            }
        }
        if (left == null || right == null) {
            return left == right;
        }
        return Objects.equals(String.valueOf(left), String.valueOf(right));
    }

    private static BigDecimal toNumber(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String s && NUMBER.matcher(s.trim()).matches()) {
            return new BigDecimal(s.trim());
        }
        return null;
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String s) {
            return !s.isEmpty() && !s.equals("false");
        }
        return true;
    }

    private static int indexOfOperator(String expr, String operator) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < expr.length() - 1; i++) {
            char c = expr.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble && expr.startsWith(operator, i)) {
                return i;
            }
        }
        return -1;
    }
}
