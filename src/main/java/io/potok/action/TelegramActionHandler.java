package io.potok.action;

import io.potok.recipient.Recipient;
import io.potok.recipient.RecipientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sends a message via the Telegram Bot API (plain HttpClient, no SDK).
 * Token comes from {@code TELEGRAM_BOT_TOKEN} only — never from YAML.
 *
 * <p>Three mutually-exclusive ways to address the message:
 * <ul>
 *   <li>{@code chat_id: "<id>"} (M1 — unchanged, no recipient lookup);</li>
 *   <li>{@code to_recipient: "<uuid or display name>"} — a single APPROVED
 *       recipient from the directory;</li>
 *   <li>{@code to: approved} — fan-out to ALL APPROVED recipients (per-recipient
 *       send; the step fails only if every send fails).</li>
 * </ul>
 * PENDING or REVOKED recipients never receive, even if referenced explicitly.
 * The recipient directory governs message routing only — it grants no Potok
 * control-plane access.
 */
@Component
public class TelegramActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramActionHandler.class);

    private final TelegramClient telegram;
    private final RecipientService recipients;

    public TelegramActionHandler(TelegramClient telegram, RecipientService recipients) {
        this.telegram = telegram;
        this.recipients = recipients;
    }

    @Override
    public String type() {
        return "telegram";
    }

    @Override
    public StepResult execute(StepContext ctx) {
        if (!telegram.isConfigured()) {
            return StepResult.fail(
                    "telegram action requires the TELEGRAM_BOT_TOKEN environment variable; "
                            + "set it (and TELEGRAM_CHAT_ID for the examples) or remove the telegram step");
        }
        String text;
        try {
            text = ctx.requireString("text");
        } catch (IllegalArgumentException e) {
            return StepResult.fail(e.getMessage());
        }

        String chatId = ctx.optionalString("chat_id", null);
        String toRecipient = ctx.optionalString("to_recipient", null);
        String to = ctx.optionalString("to", null);

        int specifiedAddresses = (isNonBlank(chatId) ? 1 : 0)
                + (isNonBlank(toRecipient) ? 1 : 0)
                + (isNonBlank(to) ? 1 : 0);
        if (specifiedAddresses == 0) {
            return StepResult.fail(
                    "telegram step needs one of 'chat_id', 'to_recipient', or 'to: approved'");
        }
        if (specifiedAddresses > 1) {
            return StepResult.fail(
                    "telegram step accepts only one of 'chat_id', 'to_recipient', 'to' — not several");
        }

        if (isNonBlank(to)) {
            if (!"approved".equalsIgnoreCase(to.trim())) {
                return StepResult.fail("'to' currently supports only 'approved'");
            }
            return broadcastToApproved(text);
        }
        if (isNonBlank(toRecipient)) {
            Optional<Recipient> match = recipients.findApprovedByIdOrName(toRecipient.trim());
            if (match.isEmpty()) {
                return StepResult.fail("no approved recipient matches 'to_recipient': "
                        + toRecipient.trim() + " (PENDING or REVOKED recipients never receive)");
            }
            return sendOne(match.get().chatId(), text, match.get().id().toString());
        }
        return sendOne(chatId, text, null);
    }

    private StepResult sendOne(String chatId, String text, String recipientId) {
        try {
            HttpResponse<String> response = telegram.sendMessage(chatId, text);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("status", response.statusCode());
                output.put("chat_id", chatId);
                if (recipientId != null) {
                    output.put("recipient_id", recipientId);
                }
                output.put("sent_count", 1);
                return StepResult.ok(output);
            }
            return StepResult.fail("telegram sendMessage returned status "
                    + response.statusCode() + ": " + response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StepResult.fail("telegram request interrupted");
        } catch (Exception e) {
            return StepResult.fail("telegram sendMessage failed: " + io.potok.common.Errors.describe(e));
        }
    }

    private StepResult broadcastToApproved(String text) {
        List<Recipient> approved = recipients.listApproved();
        if (approved.isEmpty()) {
            // step succeeds with sent_count=0 — empty fan-out is not a failure
            return StepResult.ok(Map.of(
                    "sent_count", 0,
                    "total_recipients", 0,
                    "skipped", "no approved recipients yet — approve one in the Recipients page"));
        }
        int sent = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Recipient recipient : approved) {
            try {
                HttpResponse<String> response = telegram.sendMessage(recipient.chatId(), text);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    sent++;
                } else {
                    failures.add(Map.of(
                            "recipient_id", recipient.id(),
                            "display_name", recipient.displayName(),
                            "error", "status " + response.statusCode() + ": " + response.body()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add(Map.of(
                        "recipient_id", recipient.id(),
                        "display_name", recipient.displayName(),
                        "error", "interrupted"));
                break;
            } catch (Exception e) {
                failures.add(Map.of(
                        "recipient_id", recipient.id(),
                        "display_name", recipient.displayName(),
                        "error", io.potok.common.Errors.describe(e)));
            }
        }
        log.info("telegram_broadcast sent={} failed={} total={}",
                sent, failures.size(), approved.size());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("sent_count", sent);
        output.put("total_recipients", approved.size());
        output.put("failed_count", failures.size());
        if (!failures.isEmpty()) {
            output.put("failures", failures);
        }
        if (sent == 0) {
            return StepResult.fail("telegram broadcast to approved failed for all "
                    + approved.size() + " recipients; first error: " + failures.get(0).get("error"));
        }
        return StepResult.ok(output);
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
