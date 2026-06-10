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

/** Thin Bot API sendMessage client shared by the telegram action and the DLQ notifier. */
@Component
public class TelegramClient {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper;
    private final TelegramProperties properties;

    public TelegramClient(ObjectMapper objectMapper, TelegramProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.botToken() != null && !properties.botToken().isBlank();
    }

    public String defaultChatId() {
        return properties.defaultChatId();
    }

    /**
     * Sends a message; returns the HTTP status.
     *
     * @throws IllegalStateException when no bot token is configured
     * @throws java.io.IOException   on transport failures
     */
    public HttpResponse<String> sendMessage(String chatId, String text)
            throws java.io.IOException, InterruptedException {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "telegram is not configured: set the TELEGRAM_BOT_TOKEN environment variable");
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("chat_id", chatId, "text", text));
        } catch (JsonProcessingException e) {
            throw new java.io.IOException("failed to serialize telegram payload", e);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.apiBase() + "/bot" + properties.botToken() + "/sendMessage"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
