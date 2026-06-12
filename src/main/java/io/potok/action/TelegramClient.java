package io.potok.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin Bot API client (plain HttpClient, no SDK) shared by the telegram
 * action, the DLQ notifier, approvals (inline keyboards) and the
 * callback-query poller.
 */
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
        return call("sendMessage", Map.of("chat_id", chatId, "text", text));
    }

    /**
     * Sends a message with an inline keyboard (rows of {text, url} or
     * {text, callback_data} buttons).
     */
    public HttpResponse<String> sendMessageWithButtons(String chatId, String text,
                                                       List<List<Map<String, Object>>> keyboard)
            throws java.io.IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        payload.put("reply_markup", Map.of("inline_keyboard", keyboard));
        return call("sendMessage", payload);
    }

    /** Replaces a message's text and drops its inline keyboard. */
    public HttpResponse<String> editMessageText(String chatId, long messageId, String text)
            throws java.io.IOException, InterruptedException {
        return call("editMessageText", Map.of(
                "chat_id", chatId, "message_id", messageId, "text", text));
    }

    /** The toast shown to the user who tapped an inline button. */
    public HttpResponse<String> answerCallbackQuery(String callbackQueryId, String text)
            throws java.io.IOException, InterruptedException {
        return call("answerCallbackQuery", Map.of("callback_query_id", callbackQueryId, "text", text));
    }

    /** Long-polls for updates; the request timeout leaves headroom over the poll timeout. */
    public HttpResponse<String> getUpdates(long offset, int timeoutSeconds)
            throws java.io.IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("offset", offset);
        payload.put("timeout", timeoutSeconds);
        payload.put("allowed_updates", List.of("callback_query"));
        return call("getUpdates", payload, Duration.ofSeconds(timeoutSeconds + 10L));
    }

    /** {@code result.message_id} of a sendMessage response body, or null. */
    public Long parseMessageId(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody).path("result").path("message_id");
            return node.isNumber() ? node.asLong() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private HttpResponse<String> call(String method, Map<String, Object> payload)
            throws java.io.IOException, InterruptedException {
        return call(method, payload, Duration.ofSeconds(30));
    }

    private HttpResponse<String> call(String method, Map<String, Object> payload, Duration timeout)
            throws java.io.IOException, InterruptedException {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "telegram is not configured: set the TELEGRAM_BOT_TOKEN environment variable");
        }
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new java.io.IOException("failed to serialize telegram payload", e);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.apiBase() + "/bot" + properties.botToken() + "/" + method))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
