package io.potok.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.potok.action.TelegramClient;
import io.potok.execution.ApprovalService;
import io.potok.execution.ApprovalService.Outcome;
import io.potok.recipient.RecipientService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Long-polls the Bot API for callback queries (approval taps) AND incoming
 * messages (recipient registration). Single consumer per bot token guaranteed
 * two ways:
 *
 * <ul>
 *   <li>{@link TelegramPollLock} — Postgres advisory lock prevents replicas
 *       on the same database from racing on the offset;</li>
 *   <li>409 from getUpdates (another poller or a webhook set against the bot)
 *       backs off and retries instead of crashing.</li>
 * </ul>
 *
 * Disable with {@code POTOK_TELEGRAM_POLL_UPDATES=false}: approval buttons
 * then open the one-time links, AND no recipient auto-registration happens
 * because the bot stops reading messages — operators must add recipients
 * manually (future milestone). The offset is in-memory; after a restart
 * Telegram re-delivers recent updates, decide() is one-time and recipient
 * upsert is idempotent so re-processing is harmless.
 */
@Component
public class TelegramUpdatesPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdatesPoller.class);
    private static final int POLL_TIMEOUT_SECONDS = 25;
    private static final List<String> ALLOWED_UPDATES = List.of("callback_query", "message");

    private final TelegramClient telegram;
    private final ApprovalService approvals;
    private final RecipientService recipients;
    private final TelegramPollLock lock;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration idlePause;
    private volatile boolean running = true;
    private Thread thread;

    public TelegramUpdatesPoller(TelegramClient telegram,
                                 ApprovalService approvals,
                                 RecipientService recipients,
                                 TelegramPollLock lock,
                                 ObjectMapper objectMapper,
                                 @Value("${potok.telegram.poll-updates:true}") boolean enabled,
                                 @Value("${potok.telegram.updates-idle:PT1S}") Duration idlePause) {
        this.telegram = telegram;
        this.approvals = approvals;
        this.recipients = recipients;
        this.lock = lock;
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
        lock.release();
    }

    private void loop() {
        long offset = 0;
        while (running) {
            if (!lock.tryAcquire()) {
                log.info("telegram_updates_lock_busy — waiting 60s");
                if (!sleep(Duration.ofSeconds(60))) {
                    return;
                }
                continue;
            }
            try {
                HttpResponse<String> response = telegram.getUpdates(
                        offset, POLL_TIMEOUT_SECONDS, ALLOWED_UPDATES);
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
                    JsonNode callback = update.path("callback_query");
                    if (!callback.isMissingNode()) {
                        handleCallback(callback);
                        continue;
                    }
                    JsonNode message = update.path("message");
                    if (!message.isMissingNode()) {
                        handleMessage(message);
                    }
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

    private void handleMessage(JsonNode message) {
        JsonNode chat = message.path("chat");
        if (chat.isMissingNode()) {
            return;
        }
        String chatId = chat.path("id").asText();
        if (chatId.isBlank()) {
            return;
        }
        String displayName = displayName(chat, message.path("from"));
        String text = message.path("text").asText("");
        recipients.handleBotMessage(chatId, displayName, text)
                .ifPresent(reply -> sendReply(chatId, reply));
    }

    private static String displayName(JsonNode chat, JsonNode from) {
        String username = first(chat.path("username").asText(""), from.path("username").asText(""));
        if (!username.isEmpty()) {
            return "@" + username;
        }
        String firstName = first(chat.path("first_name").asText(""), from.path("first_name").asText(""));
        String lastName = first(chat.path("last_name").asText(""), from.path("last_name").asText(""));
        String full = (firstName + " " + lastName).trim();
        if (!full.isEmpty()) {
            return full;
        }
        String title = chat.path("title").asText("");
        if (!title.isEmpty()) {
            return title;
        }
        return "chat " + chat.path("id").asText("?");
    }

    private static String first(String a, String b) {
        return a == null || a.isEmpty() ? (b == null ? "" : b) : a;
    }

    private void sendReply(String chatId, String text) {
        try {
            telegram.sendMessage(chatId, text);
        } catch (Exception e) {
            log.warn("telegram_reply_failed chatId={} error={}",
                    chatId, io.potok.common.Errors.describe(e));
        }
    }

    private void answer(String callbackId, String text) {
        try {
            telegram.answerCallbackQuery(callbackId, text);
        } catch (Exception e) {
            log.warn("telegram_answer_failed error={}", io.potok.common.Errors.describe(e));
        }
    }

    private boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
