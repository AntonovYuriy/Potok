package io.potok.action;

import io.potok.action.EmailClient.SendOutcome;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailActionHandlerTest {

    private EmailClient email;
    private EmailActionHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        email = mock(EmailClient.class);
        handler = new EmailActionHandler(email);
        when(email.isConfigured()).thenReturn(true);
        when(email.from()).thenReturn("bot@potok.io");
        Session session = Session.getInstance(new Properties());
        when(email.newMessage()).thenAnswer(invocation -> new MimeMessage(session));
        when(email.send(any(), any())).thenAnswer(invocation ->
                new SendOutcome((Address[]) invocation.getArgument(1), new Address[0]));
    }

    private StepContext ctx(Map<String, Object> with) {
        return new StepContext(UUID.randomUUID(), UUID.randomUUID(), "wf", "notify", with, 1);
    }

    @Test
    @DisplayName("fails gracefully when SMTP is not configured")
    void notConfigured() {
        when(email.isConfigured()).thenReturn(false);

        StepResult result = handler.execute(ctx(Map.of(
                "to", "a@x.com", "subject", "s", "body", "b")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not configured").contains("SMTP_");
    }

    @Test
    @DisplayName("requires subject and body")
    void requiresSubjectAndBody() {
        StepResult noSubject = handler.execute(ctx(Map.of("to", "a@x.com", "body", "b")));
        assertThat(noSubject.success()).isFalse();
        assertThat(noSubject.error()).contains("subject");

        StepResult noBody = handler.execute(ctx(Map.of("to", "a@x.com", "subject", "s")));
        assertThat(noBody.success()).isFalse();
        assertThat(noBody.error()).contains("body");
    }

    @Test
    @DisplayName("requires at least one recipient")
    void requiresRecipient() {
        StepResult result = handler.execute(ctx(Map.of("subject", "s", "body", "b")));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'to'");
    }

    @Test
    @DisplayName("rejects an invalid address with a clear error")
    void invalidAddress() {
        StepResult result = handler.execute(ctx(Map.of(
                "to", "not-an-email", "subject", "s", "body", "b")));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("invalid email address").contains("not-an-email");
    }

    @Test
    @DisplayName("de-duplicates recipients case-insensitively")
    void dedupesRecipients() throws Exception {
        StepResult result = handler.execute(ctx(Map.of(
                "to", List.of("a@x.com", "A@X.com", "b@x.com"),
                "subject", "s", "body", "b")));

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("sent_count")).isEqualTo(2);
        assertThat((List<String>) result.output().get("recipients")).containsExactly("a@x.com", "b@x.com");
    }

    @Test
    @DisplayName("a comma-separated string expands into multiple recipients")
    void splitsCommaSeparated() throws Exception {
        StepResult result = handler.execute(ctx(Map.of(
                "to", "a@x.com, b@x.com; c@x.com", "subject", "s", "body", "b")));

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("sent_count")).isEqualTo(3);
    }

    @Test
    @DisplayName("caps total recipients at 50")
    void capsRecipients() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add("user" + i + "@x.com");
        }

        StepResult result = handler.execute(ctx(Map.of(
                "to", many, "subject", "s", "body", "b")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("cap is 50");
        verify(email, never()).send(any(), any());
    }

    @Test
    @DisplayName("sends plain text by default")
    void plainTextByDefault() throws Exception {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);

        StepResult result = handler.execute(ctx(Map.of(
                "to", "a@x.com", "subject", "Hi", "body", "plain body")));

        assertThat(result.success()).isTrue();
        verify(email).send(captor.capture(), any());
        Message message = captor.getValue();
        assertThat(message.getSubject()).isEqualTo("Hi");
        assertThat(message.getContentType()).contains("text/plain");
        assertThat(message.getContent()).isEqualTo("plain body");
    }

    @Test
    @DisplayName("sends html when html: true")
    void htmlWhenFlagged() throws Exception {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);

        StepResult result = handler.execute(ctx(Map.of(
                "to", "a@x.com", "subject", "Hi", "body", "<b>hi</b>", "html", true)));

        assertThat(result.success()).isTrue();
        verify(email).send(captor.capture(), any());
        // Content-Type header is written lazily on save; the DataHandler reflects it immediately
        assertThat(captor.getValue().getDataHandler().getContentType()).contains("text/html");
    }

    @Test
    @DisplayName("html accepts a string 'true' too (YAML/template values)")
    void htmlAcceptsStringTrue() throws Exception {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);

        handler.execute(ctx(Map.of(
                "to", "a@x.com", "subject", "Hi", "body", "<b>hi</b>", "html", "true")));

        verify(email).send(captor.capture(), any());
        assertThat(captor.getValue().getDataHandler().getContentType()).contains("text/html");
    }

    @Test
    @DisplayName("reports addresses the server rejected without failing the step")
    void partialFailureReported() throws Exception {
        when(email.send(any(), any())).thenAnswer(invocation -> {
            Address[] all = invocation.getArgument(1);
            return new SendOutcome(new Address[]{all[0]}, new Address[]{all[1]});
        });

        StepResult result = handler.execute(ctx(Map.of(
                "to", List.of("good@x.com", "bad@x.com"), "subject", "s", "body", "b")));

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("sent_count")).isEqualTo(1);
        assertThat(result.output().get("failed_count")).isEqualTo(1);
        assertThat((List<String>) result.output().get("failed")).anyMatch(s -> s.contains("bad@x.com"));
    }
}
