package io.potok.execution;

import io.potok.action.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional Telegram alert when jobs enter the DLQ (POTOK_DLQ_TELEGRAM=true).
 * Rate-limited to one summary message per interval (default 1 min): events
 * inside the window are counted and reported with the next message; a window
 * with no further events sends nothing (no trailing timer by design).
 */
@Component
public class DlqNotifier {

    private static final Logger log = LoggerFactory.getLogger(DlqNotifier.class);

    private final DlqProperties properties;
    private final TelegramClient telegram;
    private final AtomicReference<Instant> lastSentAt = new AtomicReference<>(Instant.EPOCH);
    private final AtomicInteger pending = new AtomicInteger();

    public DlqNotifier(DlqProperties properties, TelegramClient telegram) {
        this.properties = properties;
        this.telegram = telegram;
    }

    @Async
    @EventListener
    public void onDeadLettered(DeadLetteredEvent event) {
        if (!properties.telegramNotify()) {
            return;
        }
        if (!telegram.isConfigured() || telegram.defaultChatId() == null || telegram.defaultChatId().isBlank()) {
            log.warn("dlq_notify_skipped reason=telegram_not_configured");
            return;
        }
        int count = pending.incrementAndGet();
        Instant last = lastSentAt.get();
        Instant now = Instant.now();
        if (now.isBefore(last.plus(properties.notifyInterval()))
                || !lastSentAt.compareAndSet(last, now)) {
            return; // inside the rate-limit window, or another thread is sending
        }
        pending.addAndGet(-count);
        String text = "Potok DLQ: %d job(s) dead-lettered. Latest: workflow '%s' step '%s' — %s"
                .formatted(count, event.workflowName(), event.stepName(), truncate(event.error()));
        try {
            telegram.sendMessage(telegram.defaultChatId(), text);
            log.info("dlq_notify_sent count={}", count);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("dlq_notify_failed error={}", e.getMessage());
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return "unknown error";
        }
        return error.length() <= 200 ? error : error.substring(0, 200) + "…";
    }
}
