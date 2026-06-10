package io.potok.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramActionHandlerTest {

    @Test
    void failsGracefullyWithoutToken() {
        TelegramActionHandler handler = new TelegramActionHandler(new TelegramClient(
                new ObjectMapper(), new TelegramProperties("", "https://api.telegram.org", "")));

        StepResult result = handler.execute(new StepContext(
                UUID.randomUUID(), "wf", "notify",
                Map.of("chat_id", "1", "text", "hi"), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("TELEGRAM_BOT_TOKEN");
    }

    @Test
    void failsWhenChatIdMissing() {
        TelegramActionHandler handler = new TelegramActionHandler(new TelegramClient(
                new ObjectMapper(), new TelegramProperties("t", "https://api.telegram.org", "")));

        StepResult result = handler.execute(new StepContext(
                UUID.randomUUID(), "wf", "notify", Map.of("text", "hi"), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("chat_id");
    }
}
