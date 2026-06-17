package io.potok.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.potok.recipient.Recipient;
import io.potok.recipient.RecipientService;
import io.potok.subscription.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramActionHandlerTest {

    private TelegramClient telegram;
    private RecipientService recipients;
    private SubscriptionService subscriptions;
    private TelegramActionHandler handler;

    @BeforeEach
    void setUp() {
        telegram = mock(TelegramClient.class);
        recipients = mock(RecipientService.class);
        subscriptions = mock(SubscriptionService.class);
        handler = new TelegramActionHandler(telegram, recipients, subscriptions);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> ok() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true}");
        return response;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> status(int code, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        when(response.body()).thenReturn(body);
        return response;
    }

    private Recipient approved(String chatId, String name) {
        return new Recipient(UUID.randomUUID(), chatId, name, Recipient.Status.APPROVED,
                "telegram", Instant.now(), Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("fails gracefully without a bot token")
    void failsGracefullyWithoutToken() {
        TelegramActionHandler unconfigured = new TelegramActionHandler(new TelegramClient(
                new ObjectMapper(), new TelegramProperties("", "https://api.telegram.org", "")),
                recipients, subscriptions);

        StepResult result = unconfigured.execute(new StepContext(null,
                UUID.randomUUID(), "wf", "notify",
                Map.of("chat_id", "1", "text", "hi"), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("TELEGRAM_BOT_TOKEN");
    }

    @Test
    @DisplayName("fails when no addressing key is provided")
    void failsWhenNoAddress() {
        when(telegram.isConfigured()).thenReturn(true);

        StepResult result = handler.execute(new StepContext(null,
                UUID.randomUUID(), "wf", "notify", Map.of("text", "hi"), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("chat_id").contains("to_recipient").contains("to: approved");
    }

    @Test
    @DisplayName("rejects combining several addressing keys")
    void rejectsAmbiguousAddressing() {
        when(telegram.isConfigured()).thenReturn(true);

        StepResult result = handler.execute(new StepContext(null,
                UUID.randomUUID(), "wf", "notify",
                Map.of("chat_id", "1", "to", "approved", "text", "hi"), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("only one of");
    }

    @Test
    @DisplayName("chat_id path is unchanged (M1 backward compatibility)")
    void chatIdPathUnchanged() throws Exception {
        HttpResponse<String> response = ok();
        when(telegram.isConfigured()).thenReturn(true);
        when(telegram.sendMessage(eq("123"), eq("hi"))).thenReturn(response);

        StepResult result = handler.execute(new StepContext(null,
                UUID.randomUUID(), "wf", "notify",
                Map.of("chat_id", "123", "text", "hi"), 1));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("chat_id", "123").containsEntry("sent_count", 1);
        verify(telegram, times(1)).sendMessage(any(), any());
    }

    @Test
    @DisplayName("to: approved fans out to ALL approved recipients only")
    void broadcastHitsApprovedOnly() throws Exception {
        HttpResponse<String> response = ok();
        when(telegram.isConfigured()).thenReturn(true);
        when(recipients.listApproved()).thenReturn(List.of(
                approved("100", "Alice"), approved("200", "Bob")));
        when(telegram.sendMessage(any(), any())).thenReturn(response);

        StepResult result = handler.execute(new StepContext(null,
                UUID.randomUUID(), "wf", "broadcast",
                Map.of("to", "approved", "text", "broadcast"), 1));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("sent_count", 2)
                .containsEntry("total_recipients", 2);
        verify(telegram).sendMessage(eq("100"), eq("broadcast"));
        verify(telegram).sendMessage(eq("200"), eq("broadcast"));
    }

    @Test
    @DisplayName("to: approved with zero recipients succeeds with sent_count=0")
    void broadcastEmptyIsNotFailure() {
        when(telegram.isConfigured()).thenReturn(true);
        when(recipients.listApproved()).thenReturn(List.of());

        StepResult result = handler.execute(new StepContext(null,
                UUID.randomUUID(), "wf", "broadcast",
                Map.of("to", "approved", "text", "hi"), 1));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("sent_count", 0);
    }

    @Test
    @DisplayName("to: approved fails when every send fails")
    void broadcastAllFailsIsFailure() throws Exception {
        HttpResponse<String> bad = status(500, "boom");
        when(telegram.isConfigured()).thenReturn(true);
        when(recipients.listApproved()).thenReturn(List.of(approved("100", "Alice")));
        when(telegram.sendMessage(any(), any())).thenReturn(bad);

        StepResult result = handler.execute(new StepContext(null,
                UUID.randomUUID(), "wf", "broadcast",
                Map.of("to", "approved", "text", "hi"), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("broadcast").contains("500");
    }

    @Test
    @DisplayName("to: approved succeeds with partial failure (step fails only if ALL fail)")
    void broadcastPartialFailureIsSuccess() throws Exception {
        HttpResponse<String> good = ok();
        HttpResponse<String> bad = status(403, "forbidden");
        when(telegram.isConfigured()).thenReturn(true);
        when(recipients.listApproved()).thenReturn(List.of(
                approved("100", "Alice"), approved("200", "Bob")));
        when(telegram.sendMessage(eq("100"), any())).thenReturn(good);
        when(telegram.sendMessage(eq("200"), any())).thenReturn(bad);

        StepResult result = handler.execute(new StepContext(null,
                UUID.randomUUID(), "wf", "broadcast",
                Map.of("to", "approved", "text", "hi"), 1));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("sent_count", 1)
                .containsEntry("failed_count", 1);
    }

    @Test
    @DisplayName("to_recipient resolves a single approved by id/name")
    void toRecipientResolvesApproved() throws Exception {
        HttpResponse<String> response = ok();
        when(telegram.isConfigured()).thenReturn(true);
        Recipient match = approved("999", "Charlie");
        when(recipients.findApprovedByIdOrName("Charlie")).thenReturn(Optional.of(match));
        when(telegram.sendMessage(eq("999"), eq("hi"))).thenReturn(response);

        StepResult result = handler.execute(new StepContext(null,
                UUID.randomUUID(), "wf", "notify",
                Map.of("to_recipient", "Charlie", "text", "hi"), 1));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("recipient_id", match.id().toString());
    }

    @Test
    @DisplayName("to_recipient fails when no approved recipient matches")
    void toRecipientUnknownFails() {
        when(telegram.isConfigured()).thenReturn(true);
        when(recipients.findApprovedByIdOrName("ghost")).thenReturn(Optional.empty());

        StepResult result = handler.execute(new StepContext(null,
                UUID.randomUUID(), "wf", "notify",
                Map.of("to_recipient", "ghost", "text", "hi"), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("no approved recipient");
    }

    @Test
    @DisplayName("'to' values other than 'approved' fail")
    void toMustBeApproved() throws Exception {
        when(telegram.isConfigured()).thenReturn(true);

        StepResult result = handler.execute(new StepContext(null,
                UUID.randomUUID(), "wf", "notify",
                Map.of("to", "pending", "text", "hi"), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'to' must be 'approved' or 'subscribers'");
        verify(telegram, never()).sendMessage(any(), any());
    }

    @SuppressWarnings("unused")
    private static IOException ioe() {
        return new IOException("boom");
    }
}
