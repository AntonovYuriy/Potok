package io.potok.trigger;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import io.potok.action.HttpActionHandler;
import io.potok.action.StepContext;
import io.potok.action.StepResult;
import io.potok.common.Json;
import io.potok.common.UrlGuard;
import io.potok.definition.TemplateResolver;
import io.potok.definition.Workflow;
import io.potok.definition.WorkflowDefinition;
import io.potok.execution.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One poll tick for a workflow. State updates and execution starts share a
 * transaction, so a fire is recorded if and only if the execution exists —
 * no double-firing across restarts.
 *
 * Resource diet (M14): ticks send If-None-Match / If-Modified-Since from the
 * stored validators and treat 304 as "no change" (no body download, no fire,
 * no state write); an unchanged 200 response also skips the poll_state write,
 * so a quiet workflow reads but never writes after its baseline.
 */
@Service
public class PollerService {

    private static final Logger log = LoggerFactory.getLogger(PollerService.class);

    private final HttpActionHandler http;
    private final PollStateRepository state;
    private final ExecutionService executions;
    private final TemplateResolver templates;
    private final Json json;
    private final HttpClient rssClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final TriggerLocks locks;
    private final UrlGuard urlGuard;

    public PollerService(HttpActionHandler http, PollStateRepository state,
                         ExecutionService executions, TemplateResolver templates, Json json,
                         TriggerLocks locks, UrlGuard urlGuard) {
        this.http = http;
        this.state = state;
        this.executions = executions;
        this.templates = templates;
        this.json = json;
        this.locks = locks;
        this.urlGuard = urlGuard;
    }

    @Transactional
    public void pollHttp(Workflow workflow) {
        if (!locks.tryAdvisoryLock(workflow.id())) {
            log.info("poll_skipped_lock workflow={}", workflow.name());
            return; // another replica is polling this workflow right now
        }
        WorkflowDefinition.Poll poll = workflow.definition().trigger().poll();
        PollStateRepository.PollState previous = state.find(workflow.id()).orElse(null);

        Map<String, Object> with = new HashMap<>(poll.http());
        with.put("fail_on_status", false); // any response is data for the poller
        addConditionalHeaders(with, previous);
        StepResult result = http.execute(new StepContext(
                workflow.id(), UUID.randomUUID(), workflow.name(), "poll", with, 1));
        if (!result.success()) {
            log.warn("poll_failed workflow={} error={}", workflow.name(), result.error());
            return; // transient fetch failure: keep previous state, try next tick
        }

        Map<String, Object> response = result.output();

        int status = response.get("status") instanceof Number n ? n.intValue() : 0;

        // Conditional GET hit: the target confirmed nothing changed since the stored
        // validators — no body was transferred, nothing to evaluate, nothing to write.
        if (status == 304) {
            log.info("poll_skipped_not_modified workflow={}", workflow.name());
            return;
        }

        // A non-2xx response is a failed fetch (rate-limit 418, 5xx, …), not data:
        // fail_on_status is off so the poller can read it, but we must not treat an
        // error page as a value. Skip the tick, keep the last good state, no fire.
        if (status < 200 || status >= 300) {
            log.info("poll_skipped_status workflow={} status={}", workflow.name(), status);
            return;
        }

        Object body = response.get("body");

        // with extract: noise in the rest of the body (timestamps, ads) is invisible
        Map<String, Object> context = new LinkedHashMap<>(response);
        Object extracted = null;
        String hashBasis;
        if (poll.extract() != null) {
            extracted = PollExtractor.extract(poll.extract(), body,
                    body instanceof String s ? s : null);
            // Absent value = "unknown", never a signal. Skip the tick so a missing
            // path doesn't fire a numeric threshold or count as a "changed" value.
            if (extracted == null) {
                log.info("poll_skipped_null_extract workflow={}", workflow.name());
                return;
            }
            context.put("value", extracted);
            hashBasis = json.write(extracted);
        } else {
            hashBasis = json.write(body);
        }
        Map<String, Object> pollView = new LinkedHashMap<>();
        pollView.put("value", extracted);
        pollView.put("body", body);
        context.put("poll", pollView);

        String newHash = sha256(hashBasis);

        PollEvaluator.Decision decision;
        if ("changed".equals(poll.fireWhen())) {
            decision = PollEvaluator.changed(previous == null ? null : previous.lastHash(), newHash);
        } else {
            boolean value = templates.evaluateCondition(poll.fireWhen(), context);
            decision = PollEvaluator.expression(
                    previous == null ? null : previous.lastCondition(), value, newHash);
        }

        String etag = headerValue(response, "etag");
        String lastModified = headerValue(response, "last-modified");
        // Write state only when something actually changed (or on the baseline);
        // a quiet tick with an identical value must not generate WAL traffic.
        if (previous == null
                || !Objects.equals(previous.lastHash(), decision.newHash())
                || !Objects.equals(previous.lastCondition(), decision.newCondition())
                || !Objects.equals(previous.etag(), etag)
                || !Objects.equals(previous.lastModified(), lastModified)) {
            state.upsert(workflow.id(), decision.newHash(), decision.newCondition(), etag, lastModified);
        }
        if (decision.fire()) {
            Map<String, Object> payload = new LinkedHashMap<>(response);
            if (poll.extract() != null) {
                payload.put("value", extracted);
            }
            executions.start(workflow, Map.of(
                    "type", "poll",
                    "fire_when", poll.fireWhen(),
                    "payload", payload));
            log.info("poll_fired workflow={} fireWhen={}", workflow.name(), poll.fireWhen());
        }
    }

    @Transactional
    public void pollRss(Workflow workflow) {
        if (!locks.tryAdvisoryLock(workflow.id())) {
            log.info("poll_skipped_lock workflow={}", workflow.name());
            return; // another replica is polling this workflow right now
        }
        WorkflowDefinition.Rss rss = workflow.definition().trigger().rss();
        PollStateRepository.PollState previous = state.find(workflow.id()).orElse(null);
        SyndFeed feed;
        String etag;
        String lastModified;
        try {
            // Same SSRF guard the http action and preview enforce: refuse a feed URL
            // that resolves to a private/internal/metadata address (honors POTOK_ALLOW_PRIVATE_URLS).
            urlGuard.check(rss.url());
            HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(rss.url()))
                    .timeout(Duration.ofSeconds(30)).GET();
            if (previous != null && previous.etag() != null) {
                request.header("If-None-Match", previous.etag());
            }
            if (previous != null && previous.lastModified() != null) {
                request.header("If-Modified-Since", previous.lastModified());
            }
            HttpResponse<byte[]> response = rssClient.send(
                    request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 304) {
                log.info("rss_poll_not_modified workflow={}", workflow.name());
                return; // feed unchanged: no body, no parse, no writes
            }
            if (response.statusCode() != 200) {
                log.warn("rss_poll_failed workflow={} status={}", workflow.name(), response.statusCode());
                return;
            }
            etag = response.headers().firstValue("etag").orElse(null);
            lastModified = response.headers().firstValue("last-modified").orElse(null);
            byte[] body = io.potok.common.HttpBodyDecoder.decode(
                    response.headers().firstValue("content-encoding").orElse(null), response.body());
            feed = new SyndFeedInput().build(new XmlReader(new java.io.ByteArrayInputStream(body)));
        } catch (UrlGuard.BlockedUrlException e) {
            log.warn("rss_poll_blocked workflow={} error={}", workflow.name(), e.getMessage());
            return; // SSRF guard: never call a private/internal target
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            log.warn("rss_poll_failed workflow={} error={}", workflow.name(), describe(e));
            return;
        }

        // first poll: baseline only — existing items are marked seen without firing
        boolean baseline = previous == null;
        Map<String, SyndEntry> entriesById = new LinkedHashMap<>();
        for (SyndEntry entry : feed.getEntries()) {
            String itemId = entry.getUri() != null && !entry.getUri().isBlank()
                    ? entry.getUri() : entry.getLink();
            if (itemId != null && !itemId.isBlank()) {
                entriesById.putIfAbsent(itemId, entry);
            }
        }
        Set<String> newIds = state.markSeenBatch(workflow.id(), entriesById.keySet());
        if (!baseline) {
            for (String itemId : newIds) {
                SyndEntry entry = entriesById.get(itemId);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("title", entry.getTitle());
                item.put("link", entry.getLink());
                item.put("date", entry.getPublishedDate() == null
                        ? null : entry.getPublishedDate().toInstant().toString());
                item.put("description", entry.getDescription() == null
                        ? null : entry.getDescription().getValue());
                executions.start(workflow, Map.of(
                        "type", "rss",
                        "payload", item));
                log.info("rss_fired workflow={} item={}", workflow.name(), itemId);
            }
        }
        // The state row is the "has polled before" marker AND the validator store;
        // write it on the baseline or when the validators moved, never on a quiet tick.
        if (baseline
                || !Objects.equals(previous.etag(), etag)
                || !Objects.equals(previous.lastModified(), lastModified)) {
            state.upsert(workflow.id(), null, null, etag, lastModified);
        }
    }

    /** Merge our conditional-GET validators into the fetch without clobbering user headers. */
    private static void addConditionalHeaders(Map<String, Object> with, PollStateRepository.PollState previous) {
        if (previous == null || (previous.etag() == null && previous.lastModified() == null)) {
            return;
        }
        Map<String, Object> headers = new LinkedHashMap<>();
        if (with.get("headers") instanceof Map<?, ?> existing) {
            existing.forEach((k, v) -> headers.put(String.valueOf(k), v));
        }
        if (previous.etag() != null) {
            headers.putIfAbsent("If-None-Match", previous.etag());
        }
        if (previous.lastModified() != null) {
            headers.putIfAbsent("If-Modified-Since", previous.lastModified());
        }
        with.put("headers", headers);
    }

    private static String headerValue(Map<String, Object> response, String name) {
        if (!(response.get("headers") instanceof Map<?, ?> headers)) {
            return null;
        }
        for (Map.Entry<?, ?> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(String.valueOf(entry.getKey())) && entry.getValue() != null) {
                return String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String describe(Exception e) {
        return io.potok.common.Errors.describe(e);
    }
}
