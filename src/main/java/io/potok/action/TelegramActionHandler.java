package io.potok.action;

import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Sends a message via the Telegram Bot API (plain HttpClient, no SDK).
 * with: chat_id, text. Token comes from TELEGRAM_BOT_TOKEN only — never from YAML.
 */
@Component
public class TelegramActionHandler implements ActionHandler {

    private final TelegramClient telegram;

    public TelegramActionHandler(TelegramClient telegram) {
        this.telegram = telegram;
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
        String chatId;
        String text;
        try {
            chatId = ctx.requireString("chat_id");
            text = ctx.requireString("text");
        } catch (IllegalArgumentException e) {
            return StepResult.fail(e.getMessage());
        }

        try {
            HttpResponse<String> response = telegram.sendMessage(chatId, text);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return StepResult.ok(Map.of("status", response.statusCode(), "chat_id", chatId));
            }
            // Bot API errors carry a human-readable "description"; surface it without echoing the token.
            return StepResult.fail("telegram sendMessage returned status "
                    + response.statusCode() + ": " + response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StepResult.fail("telegram request interrupted");
        } catch (Exception e) {
            return StepResult.fail("telegram sendMessage failed: " + io.potok.common.Errors.describe(e));
        }
    }
}
