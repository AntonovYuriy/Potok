package io.potok.action;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real SMTP round-trip against an embedded GreenMail server: the email action
 * must produce a genuine message with the right from/to/subject/body, honour
 * the html flag, and fan out to several recipients.
 */
class EmailActionGreenMailTest {

    private GreenMail greenMail;
    private EmailActionHandler handler;

    @BeforeEach
    void startServer() {
        greenMail = new GreenMail(ServerSetup.SMTP.dynamicPort());
        greenMail.start();
        EmailProperties props = new EmailProperties(
                "127.0.0.1", greenMail.getSmtp().getPort(),
                null, null, "bot@potok.io", false, false);
        SmtpConfig config = SmtpConfig.fromEnv(props);
        handler = new EmailActionHandler(new EmailClient(() -> config));
    }

    @AfterEach
    void stopServer() {
        greenMail.stop();
    }

    private StepContext ctx(Map<String, Object> with) {
        return new StepContext(UUID.randomUUID(), UUID.randomUUID(), "wf", "notify", with, 1);
    }

    @Test
    @DisplayName("sends a real plain-text message with correct envelope")
    void sendsPlainText() throws Exception {
        StepResult result = handler.execute(ctx(Map.of(
                "to", "alice@example.com",
                "subject", "Hello",
                "body", "Plain body line")));

        assertThat(result.success()).as("error: %s", result.error()).isTrue();
        assertThat(result.output().get("sent_count")).isEqualTo(1);

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage message = received[0];
        assertThat(message.getSubject()).isEqualTo("Hello");
        assertThat(message.getFrom()[0].toString()).contains("bot@potok.io");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        assertThat(message.getContent().toString().trim()).isEqualTo("Plain body line");
        assertThat(message.getContentType()).contains("text/plain");
    }

    @Test
    @DisplayName("sends html when html: true")
    void sendsHtml() throws Exception {
        StepResult result = handler.execute(ctx(Map.of(
                "to", "alice@example.com",
                "subject", "HTML",
                "body", "<b>bold</b>",
                "html", true)));

        assertThat(result.success()).isTrue();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage message = greenMail.getReceivedMessages()[0];
        assertThat(message.getContentType()).contains("text/html");
        assertThat(message.getContent().toString()).contains("<b>bold</b>");
    }

    @Test
    @DisplayName("fans out to several recipients across To/Cc")
    void fansOutToMany() throws Exception {
        StepResult result = handler.execute(ctx(Map.of(
                "to", List.of("a@example.com", "b@example.com"),
                "cc", "c@example.com",
                "subject", "Broadcast",
                "body", "hi all")));

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("sent_count")).isEqualTo(3);
        // GreenMail records one delivered message per recipient
        assertThat(greenMail.waitForIncomingEmail(5000, 3)).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(3);
    }
}
