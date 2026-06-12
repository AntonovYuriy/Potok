package io.potok.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.potok.action.TelegramClient;
import io.potok.execution.ApprovalService;
import io.potok.execution.ApprovalService.Outcome;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Long-polls the Bot API for callback queries so approval buttons answer
 * right in the chat — tap → toast → the message loses its buttons and shows
 * the outcome. Single consumer per bot token: a 409 from getUpdates (another
 * poller or a configured webhook) backs off and retries instead of crashing.
 * Disable with POTOK_TELEGRAM_POLL_UPDATES=false (buttons then open the
 * one-time links instead).
 *
 * The offset is in-memory only: after a restart Telegram re-delivers recent
 * updates and the one-time decide() makes re-processing harmless.
 */
@Component
public class TelegramUpdatesPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdatesPoller.class);
    private static final int POLL_TIMEOUT_SECONDS = 25;

    private final TelegramClient telegram;
    private final ApprovalService approvals;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration idlePause;
    private volatile boolean running = true;
    private Thread thread;

    public TelegramUpdatesPoller(TelegramClient telegram,
                                 ApprovalService approvals,
                                 ObjectMapper objectMapper,
                                 @Value("${potok.telegram.poll-updates:true}") boolean enabled,
                                 @Value("${potok.telegram.updates-idle:PT1S}") Duration idlePause) {
        this.telegram = telegram;
        this.approvals = approvals;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.idlePause = idlePause;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled || !telegram.isConfigured()) {
            log.info("telegram_updates_poller_disabled enabled={} configured={}",
                    enabled, telegram.isConfigured());
            return;
        }
        thread = Thread.ofVirtual().name("telegram-updates").start(this::loop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        long offset = 0;
        while (running) {
            try {
                HttpResponse<String> response = telegram.getUpdates(offset, POLL_TIMEOUT_SECONDS);
                if (response.statusCode() == 409) {
                    // another getUpdates consumer or a webhook owns this bot right now
                    log.warn("telegram_updates_conflict — backing off 60s (another poller or a webhook is set)");
                    sleep(Duration.ofSeconds(60));
                    continue;
                }
                if (response.statusCode() != 200) {
                    log.warn("telegram_updates_failed status={}", response.statusCode());
                    sleep(Duration.ofSeconds(5));
                    continue;
                }
                JsonNode updates = objectMapper.readTree(response.body()).path("result");
                if (!updates.isArray() || updates.isEmpty()) {
                    sleep(idlePause); // stubs answer instantly; the real API long-polls
                    continue;
                }
                for (JsonNode update : updates) {
                    offset = Math.max(offset, update.path("update_id").asLong() + 1);
                    handleCallback(update.path("callback_query"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("telegram_updates_error error={}", io.potok.common.Errors.describe(e));
                sleep(Duration.ofSeconds(5));
            }
        }
    }

    private void handleCallback(JsonNode callback) {
        if (callback.isMissingNode()) {
            return;
        }
        String callbackId = callback.path("id").asText();
        String data = callback.path("data").asText("");
        boolean approve = data.startsWith("apr:");
        boolean deny = data.startsWith("dny:");
        if (!approve && !deny) {
            answer(callbackId, "Unknown button");
            return;
        }
        Outcome outcome = approvals.decideByToken(data.substring(4));
        switch (outcome.status()) {
            case DECIDED -> {
                answer(callbackId, outcome.approved() ? "Approved ✅" : "Denied ❌");
                approvals.reflectDecisionInChat(outcome.approval().id(),
                        outcome.approved() ? "✅ Approved" : "❌ Denied");
            }
            case ALREADY_DECIDED -> answer(callbackId,
                    "Already decided (" + outcome.approval().decision() + ")");
            case EXPIRED -> answer(callbackId, "⌛ This approval has expired");
            case UNKNOWN -> answer(callbackId, "This button is no longer valid");
        }
    }

    private void answer(String callbackId, String text) {
        try {
            telegram.answerCallbackQuery(callbackId, text);
        } catch (Exception e) {
            log.warn("telegram_answer_failed error={}", io.potok.common.Errors.describe(e));
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
