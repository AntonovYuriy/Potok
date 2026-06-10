package io.potok.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Sends a message via the Telegram Bot API (plain HttpClient, no SDK).
 * with: chat_id, text. Token comes from TELEGRAM_BOT_TOKEN only — never from YAML.
 */
@Component
public class TelegramActionHandler implements ActionHandler {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper;
    private final TelegramProperties properties;

    public TelegramActionHandler(ObjectMapper objectMapper, TelegramProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String type() {
        return "telegram";
    }

    @Override
    public StepResult execute(StepContext ctx) {
        if (properties.botToken() == null || properties.botToken().isBlank()) {
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

        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("chat_id", chatId, "text", text));
        } catch (JsonProcessingException e) {
            return StepResult.fail("failed to serialize telegram payload: " + e.getMessage());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.apiBase() + "/bot" + properties.botToken() + "/sendMessage"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
            return StepResult.fail("telegram sendMessage failed: " + e.getMessage());
        }
    }
}
