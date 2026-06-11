package io.potok.preview;

import io.potok.definition.WorkflowDefinition;

import java.util.List;
import java.util.Map;

/** Pure text mapping for preview results: human first, technical detail second. */
final class PreviewTexts {

    private PreviewTexts() {
    }

    /** One plain-language line about when this workflow actually fires. */
    static String triggerNote(WorkflowDefinition.Trigger trigger) {
        if (trigger.poll() != null) {
            WorkflowDefinition.Poll poll = trigger.poll();
            String every = humanInterval(poll.interval());
            if ("changed".equals(poll.fireWhen())) {
                return "You'll get a message when this value CHANGES between checks (every " + every + ").";
            }
            return "You'll get a message when \"" + poll.fireWhen()
                    + "\" becomes true (checked every " + every + ").";
        }
        if (trigger.rss() != null) {
            return "Fires once per NEW feed item (checked every "
                    + humanInterval(trigger.rss().interval()) + ").";
        }
        if (trigger.cron() != null) {
            return "Runs on schedule \"" + trigger.cron() + "\".";
        }
        return "Runs when the webhook receives a delivery (preview uses an empty payload).";
    }

    static String humanInterval(java.time.Duration interval) {
        long seconds = interval.toSeconds();
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    /** Short human reading of a raw step error; the raw text goes into detail. */
    static String humanizeError(String error) {
        if (error == null) {
            return "Step failed";
        }
        String lower = error.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("private/internal address")) {
            return "URL blocked: it points to a private/internal address";
        }
        if (lower.contains("unknownhost") || lower.contains("unknown host")
                || lower.contains("unresolvedaddress") || lower.contains("nodename")
                || lower.contains("name or service not known")) {
            return "Host not found — check the URL";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "The request timed out";
        }
        if (lower.contains("connection refused") || lower.contains("connectexception")
                || lower.contains("connect failed")) {
            return "Couldn't reach the server";
        }
        if (lower.contains("certificate") || lower.contains("sslhandshake")) {
            return "TLS handshake failed";
        }
        return "Step failed";
    }

    static String httpSummary(String method, String url, Object status) {
        String code = String.valueOf(status);
        String verdict = code.startsWith("2") ? code + " OK" : "status " + code;
        return method + " " + shorten(url) + " — " + verdict;
    }

    static String sslSummary(Map<String, Object> output) {
        return "Certificate for " + output.get("host") + " is valid until "
                + output.get("not_after") + " (" + output.get("days_left") + " days left)";
    }

    static String wasteSummary(Map<String, Object> output) {
        Object summary = output.get("summary");
        if (Boolean.TRUE.equals(output.get("has_collection")) && summary != null) {
            return String.valueOf(summary);
        }
        return "No collection " + output.get("when") + " (" + output.get("date") + ")";
    }

    static String telegramSummary(String text) {
        return "Message that WOULD be sent (not sent in preview): \"" + text + "\"";
    }

    static String simulatedHttpSummary(String method, String url) {
        return "Would send " + method + " to " + shorten(url) + " (not sent in preview)";
    }

    static String conditionSummary(String kind, String condition, Object currentValue) {
        String current = currentValue == null ? "" : " (current: " + currentValue + ")";
        if ("telegram".equals(kind)) {
            return "Condition \"" + condition + "\" — NOT met right now" + current
                    + " → no message would be sent";
        }
        return "Condition \"" + condition + "\" — NOT met right now" + current
                + " → this step would be skipped";
    }

    static String dependencySkipSummary(String failedStep) {
        return "Would be skipped — depends on failed step \"" + failedStep + "\"";
    }

    /** Poll-fetch summary: status plus what the extractor saw. */
    static String pollFetchSummary(Object status, boolean hasExtract, Object extracted,
                                   WorkflowDefinition.Extract extract) {
        String base = "Fetched — status " + status;
        if (!hasExtract) {
            return base;
        }
        String selector = extract.css() != null ? extract.css() : extract.jsonpath();
        if (extracted == null) {
            return base + ", but \"" + selector + "\" matched NOTHING — check the selector";
        }
        return base + ", element found: \"" + extracted + "\"";
    }

    static String shorten(String url) {
        return url != null && url.length() > 90 ? url.substring(0, 87) + "…" : url;
    }

    /** Cap any string in a step output so the preview response stays small. */
    static Object truncate(Object value, int maxChars) {
        if (value instanceof String s) {
            return s.length() > maxChars ? s.substring(0, maxChars) + "… (truncated)" : s;
        }
        if (value instanceof Map<?, ?> map) {
            java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), truncate(v, maxChars)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> truncate(item, maxChars)).toList();
        }
        return value;
    }
}
