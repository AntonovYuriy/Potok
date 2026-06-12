package io.potok.preview;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import io.potok.action.ActionHandler;
import io.potok.action.ActionRegistry;
import io.potok.action.HttpActionHandler;
import io.potok.action.StepContext;
import io.potok.action.StepResult;
import io.potok.common.Json;
import io.potok.common.UrlGuard;
import io.potok.definition.InvalidDefinitionException;
import io.potok.definition.TemplateResolver;
import io.potok.definition.WorkflowDefinition;
import io.potok.definition.YamlDefinitionParser;
import io.potok.preview.PreviewResult.StepPreview;
import io.potok.preview.PreviewResult.TriggerPreview;
import io.potok.trigger.PollExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dry run "what would happen right now": validates the YAML like create,
 * walks the DAG once synchronously in-process — nothing touches the queue,
 * the workflow table or poll state.
 *
 * Read-only actions (http GET, the poll fetch, ssl_check, warsaw_waste) run
 * for real; side effects (telegram, non-GET http, unknown/custom actions) are
 * described but not performed. Single attempt per step, no retries; failures
 * become result entries, not API errors. Wall clock is capped (default 10s) —
 * steps past the deadline are reported as skipped.
 */
@Service
public class PreviewService {

    static final int MAX_STEPS = 10;
    private static final int MAX_STRING_CHARS = 4_000; // per-string cap keeps the response well under 256KB
    private static final Set<String> READ_ONLY_ACTIONS = Set.of("ssl_check", "warsaw_waste");
    private static final Pattern CONTEXT_PATH =
            Pattern.compile("\\b(?:steps|trigger|poll)\\.[A-Za-z0-9_.]+");

    private final YamlDefinitionParser parser;
    private final ActionRegistry actions;
    private final HttpActionHandler http;
    private final TemplateResolver templates;
    private final UrlGuard urlGuard;
    private final Json json;
    private final Duration budget;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient rssClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public PreviewService(YamlDefinitionParser parser,
                          ActionRegistry actions,
                          HttpActionHandler http,
                          TemplateResolver templates,
                          UrlGuard urlGuard,
                          Json json,
                          @Value("${potok.preview.timeout:PT10S}") Duration budget) {
        this.parser = parser;
        this.actions = actions;
        this.http = http;
        this.templates = templates;
        this.urlGuard = urlGuard;
        this.json = json;
        this.budget = budget;
    }

    public PreviewResult preview(String yamlSource) {
        WorkflowDefinition definition = parser.parse(yamlSource);
        if (definition.steps().size() > MAX_STEPS) {
            throw new InvalidDefinitionException("preview supports at most " + MAX_STEPS
                    + " steps, this workflow has " + definition.steps().size());
        }
        Instant deadline = Instant.now().plus(budget);

        TriggerContext trigger = previewTrigger(definition.trigger(), deadline);
        Map<String, Object> stepOutputs = new LinkedHashMap<>();
        Map<String, StepState> states = new HashMap<>();
        List<StepPreview> results = new ArrayList<>();

        // engine-like rounds: run every step whose needs are all satisfied;
        // the DAG is validated acyclic at parse time, so this terminates
        while (states.size() < definition.steps().size()) {
            boolean progressed = false;
            for (WorkflowDefinition.Step step : definition.steps()) {
                if (states.containsKey(step.name())) {
                    continue;
                }
                List<String> needs = definition.effectiveNeeds(step.name());
                if (!needs.stream().allMatch(states::containsKey)) {
                    continue;
                }
                progressed = true;
                String failedNeed = needs.stream()
                        .filter(n -> states.get(n) == StepState.FAILED).findFirst().orElse(null);
                if (failedNeed != null) {
                    states.put(step.name(), StepState.SKIPPED);
                    results.add(StepPreview.skipped(step.name(), step.action(),
                            PreviewTexts.dependencySkipSummary(failedNeed), null));
                    continue;
                }
                StepPreview preview = previewStep(step, trigger.payload, stepOutputs, deadline);
                results.add(preview);
                states.put(step.name(), "failed".equals(preview.mode())
                        ? StepState.FAILED : StepState.SATISFIED);
            }
            if (!progressed) {
                break; // defensive: should not happen with a validated DAG
            }
        }
        return new PreviewResult(trigger.preview, results);
    }

    private enum StepState { SATISFIED, FAILED, SKIPPED }

    private record TriggerContext(TriggerPreview preview, Object payload) {
    }

    /**
     * Poll/rss triggers fetch for real so step templates see realistic
     * {@code trigger.*} values; cron/webhook previews use an empty payload.
     */
    private TriggerContext previewTrigger(WorkflowDefinition.Trigger trigger, Instant deadline) {
        String note = PreviewTexts.triggerNote(trigger);
        if (trigger.poll() != null) {
            return previewPoll(trigger.poll(), note, deadline);
        }
        if (trigger.rss() != null) {
            return previewRss(trigger.rss(), note, deadline);
        }
        String kind = trigger.cron() != null ? "cron" : "webhook";
        return new TriggerContext(new TriggerPreview(kind, note, null, null), Map.of());
    }

    private TriggerContext previewPoll(WorkflowDefinition.Poll poll, String note, Instant deadline) {
        Map<String, Object> with = new HashMap<>(poll.http());
        with.put("fail_on_status", false);
        StepResult result = callWithDeadline(http,
                new StepContext(UUID.randomUUID(), "preview", "poll", with, 1), deadline);
        if (!result.success()) {
            return new TriggerContext(new TriggerPreview("poll", note,
                    PreviewTexts.humanizeError(result.error()), result.error()), Map.of());
        }
        Map<String, Object> response = result.output();
        Object body = response.get("body");
        Object extracted = poll.extract() == null ? null
                : PollExtractor.extract(poll.extract(), body, body instanceof String s ? s : null);

        // same payload shape PollerService produces when it fires
        Map<String, Object> payload = new LinkedHashMap<>(response);
        Map<String, Object> condContext = new LinkedHashMap<>(response);
        if (poll.extract() != null) {
            payload.put("value", extracted);
            condContext.put("value", extracted);
        }
        Map<String, Object> pollView = new LinkedHashMap<>();
        pollView.put("value", extracted);
        pollView.put("body", body);
        condContext.put("poll", pollView);

        String summary = PreviewTexts.pollFetchSummary(
                response.get("status"), poll.extract() != null, extracted, poll.extract());
        String detail = null;
        if (!"changed".equals(poll.fireWhen())) {
            boolean met;
            try {
                met = templates.evaluateCondition(poll.fireWhen(), condContext);
            } catch (RuntimeException e) {
                met = false;
            }
            summary += met
                    ? ". Fire condition is TRUE right now (fires when it turns true after being false)"
                    : ". Fire condition is NOT met right now → no message would be sent";
            detail = "fire_when: " + poll.fireWhen();
        }
        return new TriggerContext(new TriggerPreview("poll", note, summary, detail),
                PreviewTexts.truncate(payload, MAX_STRING_CHARS));
    }

    private TriggerContext previewRss(WorkflowDefinition.Rss rss, String note, Instant deadline) {
        try {
            urlGuard.check(rss.url());
            long remaining = Math.max(1, Duration.between(Instant.now(), deadline).toMillis());
            HttpResponse<byte[]> response = rssClient.send(
                    HttpRequest.newBuilder().uri(URI.create(rss.url()))
                            .timeout(Duration.ofMillis(remaining)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            SyndFeed feed = new SyndFeedInput()
                    .build(new XmlReader(new java.io.ByteArrayInputStream(response.body())));
            if (feed.getEntries().isEmpty()) {
                return new TriggerContext(new TriggerPreview("rss", note,
                        "Feed fetched, but it has no items yet", null), Map.of());
            }
            SyndEntry latest = feed.getEntries().get(0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", latest.getTitle());
            item.put("link", latest.getLink());
            item.put("date", latest.getPublishedDate() == null
                    ? null : latest.getPublishedDate().toInstant().toString());
            item.put("description", latest.getDescription() == null
                    ? null : latest.getDescription().getValue());
            return new TriggerContext(new TriggerPreview("rss", note,
                    "Feed fetched — preview uses the latest item: \"" + latest.getTitle() + "\"", null),
                    PreviewTexts.truncate(item, MAX_STRING_CHARS));
        } catch (UrlGuard.BlockedUrlException e) {
            return new TriggerContext(new TriggerPreview("rss", note,
                    PreviewTexts.humanizeError(e.getMessage()), e.getMessage()), Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TriggerContext(new TriggerPreview("rss", note,
                    "Feed fetch interrupted", null), Map.of());
        } catch (Exception e) {
            String error = io.potok.common.Errors.describe(e);
            return new TriggerContext(new TriggerPreview("rss", note,
                    PreviewTexts.humanizeError(error), error), Map.of());
        }
    }

    private StepPreview previewStep(WorkflowDefinition.Step step, Object triggerPayload,
                                    Map<String, Object> stepOutputs, Instant deadline) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("trigger", triggerPayload);
        context.put("steps", stepOutputs);

        if (step.condition() != null) {
            boolean met;
            try {
                met = templates.evaluateCondition(step.condition(), context);
            } catch (RuntimeException e) {
                return StepPreview.failed(step.name(), step.action(),
                        "Condition could not be evaluated", io.potok.common.Errors.describe(e));
            }
            if (!met) {
                // engine semantics: a condition-skip still satisfies dependents
                stepOutputs.putIfAbsent(step.name(), Map.of());
                return StepPreview.skipped(step.name(), step.action(),
                        PreviewTexts.conditionSummary(step.action(), step.condition(),
                                firstCurrentValue(step.condition(), context)),
                        currentValuesDetail(step.condition(), context));
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> input = step.with() == null
                ? Map.of()
                : (Map<String, Object>) templates.resolve(step.with(), context);

        if (step.waitFor() != null) {
            stepOutputs.put(step.name(), Map.of("simulated", true));
            return StepPreview.simulated(step.name(), "wait",
                    "Would sleep " + io.potok.execution.ApprovalService.humanDuration(step.waitFor())
                            + ", then continue (not slept in preview)", null, null);
        }
        if ("approval".equals(step.action())) {
            java.time.Duration timeout = java.util.Optional.ofNullable(
                            io.potok.definition.YamlDefinitionParser.parseDuration(
                                    step.name(), "timeout", input.get("timeout")))
                    .orElse(io.potok.execution.ApprovalService.DEFAULT_TIMEOUT);
            stepOutputs.put(step.name(), Map.of("approved", true, "timed_out", false, "simulated", true));
            Map<String, Object> rendered = new LinkedHashMap<>();
            rendered.put("question", input.get("text"));
            return StepPreview.simulated(step.name(), "approval",
                    "Would ask for approval in Telegram and wait up to "
                            + io.potok.execution.ApprovalService.humanDuration(timeout)
                            + " (nothing sent in preview)",
                    "Steps below are previewed for the APPROVED path; a denial or timeout takes the other branch.",
                    rendered);
        }
        if (Instant.now().isAfter(deadline)) {
            return StepPreview.skipped(step.name(), step.action(),
                    "Not run — preview hit the " + budget.toSeconds() + "s time limit", null);
        }
        return switch (dispatch(step.action(), input)) {
            case EXECUTE -> executeReadOnly(step, input, deadline, stepOutputs);
            case SIMULATE_TELEGRAM -> simulateTelegram(step, input, stepOutputs);
            case SIMULATE_HTTP -> simulateHttp(step, input, stepOutputs);
            case SIMULATE_UNKNOWN -> simulateUnknown(step, input, stepOutputs);
        };
    }

    private enum Dispatch { EXECUTE, SIMULATE_TELEGRAM, SIMULATE_HTTP, SIMULATE_UNKNOWN }

    private Dispatch dispatch(String action, Map<String, Object> input) {
        if ("telegram".equals(action)) {
            return Dispatch.SIMULATE_TELEGRAM;
        }
        if ("http".equals(action)) {
            String method = String.valueOf(input.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
            return method.equals("GET") || method.equals("HEAD")
                    ? Dispatch.EXECUTE : Dispatch.SIMULATE_HTTP;
        }
        // only known read-only actions run for real; custom handlers might have side effects
        return READ_ONLY_ACTIONS.contains(action) && actions.find(action) != null
                ? Dispatch.EXECUTE : Dispatch.SIMULATE_UNKNOWN;
    }

    private StepPreview executeReadOnly(WorkflowDefinition.Step step, Map<String, Object> input,
                                        Instant deadline, Map<String, Object> stepOutputs) {
        ActionHandler handler = actions.find(step.action());
        StepResult result = callWithDeadline(handler,
                new StepContext(UUID.randomUUID(), "preview", step.name(), input, 1), deadline);
        if (!result.success()) {
            return StepPreview.failed(step.name(), step.action(),
                    PreviewTexts.humanizeError(result.error()), result.error());
        }
        Map<String, Object> output = result.output() == null ? Map.of() : result.output();
        stepOutputs.put(step.name(), output);
        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = (Map<String, Object>) PreviewTexts.truncate(output, MAX_STRING_CHARS);
        return StepPreview.executed(step.name(), step.action(),
                summarizeExecuted(step.action(), input, output), null, rendered);
    }

    private String summarizeExecuted(String action, Map<String, Object> input, Map<String, Object> output) {
        return switch (action) {
            case "http" -> PreviewTexts.httpSummary(
                    String.valueOf(input.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT),
                    String.valueOf(input.get("url")), output.get("status"));
            case "ssl_check" -> PreviewTexts.sslSummary(output);
            case "warsaw_waste" -> PreviewTexts.wasteSummary(output);
            default -> "Executed";
        };
    }

    private StepPreview simulateTelegram(WorkflowDefinition.Step step, Map<String, Object> input,
                                         Map<String, Object> stepOutputs) {
        String text = String.valueOf(input.getOrDefault("text", ""));
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("chat_id", input.get("chat_id"));
        rendered.put("text", PreviewTexts.truncate(text, MAX_STRING_CHARS));
        stepOutputs.put(step.name(), Map.of("simulated", true));
        return StepPreview.simulated(step.name(), "telegram",
                PreviewTexts.telegramSummary(String.valueOf(
                        PreviewTexts.truncate(text, 500))), null, rendered);
    }

    private StepPreview simulateHttp(WorkflowDefinition.Step step, Map<String, Object> input,
                                     Map<String, Object> stepOutputs) {
        String method = String.valueOf(input.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
        String url = String.valueOf(input.get("url"));
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("method", method);
        rendered.put("url", url);
        if (input.get("body") != null) {
            rendered.put("body", PreviewTexts.truncate(input.get("body"), MAX_STRING_CHARS));
        }
        if (input.get("headers") != null) {
            rendered.put("headers", input.get("headers"));
        }
        stepOutputs.put(step.name(), Map.of("simulated", true));
        return StepPreview.simulated(step.name(), "http",
                PreviewTexts.simulatedHttpSummary(method, url),
                "Downstream steps see no response from a simulated request.", rendered);
    }

    private StepPreview simulateUnknown(WorkflowDefinition.Step step, Map<String, Object> input,
                                        Map<String, Object> stepOutputs) {
        stepOutputs.put(step.name(), Map.of("simulated", true));
        boolean known = actions.find(step.action()) != null;
        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = (Map<String, Object>) PreviewTexts.truncate(input, MAX_STRING_CHARS);
        return StepPreview.simulated(step.name(), step.action(),
                known
                        ? "Would run action \"" + step.action() + "\" (not executed in preview)"
                        : "Unknown action \"" + step.action() + "\" — this step would fail",
                known ? "Custom actions are not executed in preview — they might have side effects."
                        : "Available actions: " + actions.types(),
                rendered);
    }

    /** Runs a handler with the remaining time budget; a timeout becomes a failed StepResult. */
    private StepResult callWithDeadline(ActionHandler handler, StepContext ctx, Instant deadline) {
        long remaining = Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) {
            return StepResult.fail("preview time limit reached");
        }
        Future<StepResult> future = executor.submit(() -> handler.execute(ctx));
        try {
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return StepResult.fail("timed out — the preview waits at most "
                    + budget.toSeconds() + "s in total");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return StepResult.fail("preview interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            return StepResult.fail(io.potok.common.Errors.describe(e.getCause()));
        }
    }

    /** First context value referenced by the condition — the "current: 249" part. */
    private Object firstCurrentValue(String condition, Map<String, Object> context) {
        Matcher matcher = CONTEXT_PATH.matcher(condition);
        if (matcher.find()) {
            return templates.resolve("{{ " + matcher.group() + " }}", context);
        }
        return null;
    }

    /** All referenced context paths with their current values, for the collapsed detail. */
    private String currentValuesDetail(String condition, Map<String, Object> context) {
        Map<String, Object> values = new LinkedHashMap<>();
        Matcher matcher = CONTEXT_PATH.matcher(condition);
        Set<String> seen = new LinkedHashSet<>();
        while (matcher.find()) {
            String path = matcher.group();
            if (seen.add(path)) {
                values.put(path, PreviewTexts.truncate(
                        templates.resolve("{{ " + path + " }}", context), 500));
            }
        }
        return values.isEmpty() ? null : "current values: " + json.write(values);
    }
}
