package io.potok.action;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** apiBase is overridable so tests (and self-hosted Bot API servers) can point elsewhere. */
@ConfigurationProperties(prefix = "potok.telegram")
public record TelegramProperties(String botToken, String apiBase, String defaultChatId) {
}
