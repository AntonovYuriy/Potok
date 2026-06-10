package io.potok.trigger;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import io.potok.action.HttpActionHandler;
import io.potok.action.StepContext;
import io.potok.action.StepResult;
import io.potok.common.Json;
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
import java.util.UUID;

/**
 * One poll tick for a workflow. State updates and execution starts share a
 * transaction, so a fire is recorded if and only if the execution exists —
 * no double-firing across restarts.
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

    public PollerService(HttpActionHandler http, PollStateRepository state,
                         ExecutionService executions, TemplateResolver templates, Json json,
                         TriggerLocks locks) {
        this.http = http;
        this.state = state;
        this.executions = executions;
        this.templates = templates;
        this.json = json;
        this.locks = locks;
    }

    @Transactional
    public void pollHttp(Workflow workflow) {
        if (!locks.tryAdvisoryLock(workflow.id())) {
            log.info("poll_skipped_lock workflow={}", workflow.name());
            return; // another replica is polling this workflow right now
        }
        WorkflowDefinition.Poll poll = workflow.definition().trigger().poll();
        Map<String, Object> with = new HashMap<>(poll.http());
        with.put("fail_on_status", false); // any response is data for the poller
        StepResult result = http.execute(new StepContext(
                UUID.randomUUID(), workflow.name(), "poll", with, 1));
        if (!result.success()) {
            log.warn("poll_failed workflow={} error={}", workflow.name(), result.error());
            return; // transient fetch failure: keep previous state, try next tick
        }

        Map<String, Object> response = result.output();
        Object body = response.get("body");

        // with extract: noise in the rest of the body (timestamps, ads) is invisible
        Map<String, Object> context = new LinkedHashMap<>(response);
        Object extracted = null;
        String hashBasis;
        if (poll.extract() != null) {
            extracted = PollExtractor.extract(poll.extract(), body,
                    body instanceof String s ? s : null);
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
        PollStateRepository.PollState previous = state.find(workflow.id()).orElse(null);

        PollEvaluator.Decision decision;
        if ("changed".equals(poll.fireWhen())) {
            decision = PollEvaluator.changed(previous == null ? null : previous.lastHash(), newHash);
        } else {
            boolean value = templates.evaluateCondition(poll.fireWhen(), context);
            decision = PollEvaluator.expression(
                    previous == null ? null : previous.lastCondition(), value, newHash);
        }

        state.upsert(workflow.id(), decision.newHash(), decision.newCondition());
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
        SyndFeed feed;
        try {
            HttpResponse<byte[]> response = rssClient.send(
                    HttpRequest.newBuilder().uri(URI.create(rss.url()))
                            .timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("rss_poll_failed workflow={} status={}", workflow.name(), response.statusCode());
                return;
            }
            feed = new SyndFeedInput().build(new XmlReader(new java.io.ByteArrayInputStream(response.body())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            log.warn("rss_poll_failed workflow={} error={}", workflow.name(), describe(e));
            return;
        }

        // first poll: baseline only — existing items are marked seen without firing
        boolean baseline = !state.hasPolledBefore(workflow.id());
        for (SyndEntry entry : feed.getEntries()) {
            String itemId = entry.getUri() != null && !entry.getUri().isBlank()
                    ? entry.getUri() : entry.getLink();
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            boolean isNew = state.markSeen(workflow.id(), itemId);
            if (isNew && !baseline) {
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
        state.touch(workflow.id());
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
