package io.potok;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * One workflow, two delivery channels (telegram + email) as independent root
 * steps. Proves each channel fires on its own and that a failure in one channel
 * never blocks the other — closing M8 audit GAP item 11.
 */
class MultiChannelIntegrationTest extends IntegrationTestBase {

    private static final GreenMail GREEN_MAIL = new GreenMail(ServerSetup.SMTP.dynamicPort());

    static {
        GREEN_MAIL.start();
    }

    @DynamicPropertySource
    static void smtpProperties(DynamicPropertyRegistry registry) {
        registry.add("potok.smtp.host", () -> "127.0.0.1");
        registry.add("potok.smtp.port", () -> GREEN_MAIL.getSmtp().getPort());
        registry.add("potok.smtp.from", () -> "potok@example.com");
        registry.add("potok.smtp.auth", () -> false);
        registry.add("potok.smtp.starttls", () -> false);
    }

    @AfterEach
    void resetMail() throws Exception {
        // purge messages WITHOUT restarting the server — reset() would rebind the
        // dynamic SMTP port, breaking the port the app bound at context startup.
        GREEN_MAIL.purgeEmailFromAllMailboxes();
    }

    /** Webhook → two independent roots: telegram and email. {@code email} address is parameterised. */
    private String yaml(String path, String emailTo) {
        return """
                name: %s
                trigger:
                  webhook: { path: "%s" }
                steps:
                  - name: telegram_alert
                    action: telegram
                    needs: []
                    retry: { max_attempts: 3, base_delay: 400ms, max_delay: 2s }
                    with:
                      chat_id: "42"
                      text: "tg: {{ trigger.kind }}"
                  - name: email_alert
                    action: email
                    needs: []
                    with:
                      to: "%s"
                      subject: "mail: {{ trigger.kind }}"
                      body: "value {{ trigger.kind }}"
                """.formatted(path, path, emailTo);
    }

    private void telegramReturns(int status) {
        WIRE_MOCK.stubFor(post(urlPathMatching("/bot.*/sendMessage"))
                .willReturn(aResponse().withStatus(status).withBody("{\"ok\": " + (status < 300) + "}")));
    }

    private String triggerAndAwaitTerminal(String path) {
        ResponseEntity<Map<String, Object>> triggered =
                postJson("/hooks/" + path, Map.of("kind", "deploy"));
        assertThat(triggered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String executionId = (String) triggered.getBody().get("executionId");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status"))
                        .isIn("SUCCEEDED", "FAILED"));
        return executionId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stepByName(String executionId, String name) {
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) getExecution(executionId).get("steps");
        return steps.stream().filter(s -> name.equals(s.get("name"))).findFirst().orElseThrow();
    }

    @Test
    void bothChannelsFireIndependently() throws Exception {
        telegramReturns(200);
        assertThat(postYaml("/api/workflows", yaml("mc-both", "ops@example.com")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        String executionId = triggerAndAwaitTerminal("mc-both");

        assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED");
        assertThat(stepByName(executionId, "telegram_alert").get("status")).isEqualTo("SUCCEEDED");
        assertThat(stepByName(executionId, "email_alert").get("status")).isEqualTo("SUCCEEDED");

        // telegram hit the Bot API once; email landed in SMTP once
        WIRE_MOCK.verify(1, postRequestedFor(urlPathMatching("/bot.*/sendMessage")));
        assertThat(GREEN_MAIL.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo("mail: deploy");
    }

    @Test
    void telegramFailureDoesNotBlockEmail() throws Exception {
        telegramReturns(500); // Bot API rejects every send → telegram step exhausts retries
        assertThat(postYaml("/api/workflows", yaml("mc-tgfail", "ops@example.com")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        String executionId = triggerAndAwaitTerminal("mc-tgfail");

        // the execution fails because one step failed, but the channels are independent:
        assertThat(getExecution(executionId).get("status")).isEqualTo("FAILED");
        assertThat(stepByName(executionId, "telegram_alert").get("status")).isEqualTo("FAILED");
        Map<String, Object> emailStep = stepByName(executionId, "email_alert");
        assertThat(emailStep.get("status"))
                .as("email_alert error: %s", emailStep.get("error"))
                .isEqualTo("SUCCEEDED");

        // email was delivered despite the telegram failure
        assertThat(GREEN_MAIL.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(GREEN_MAIL.getReceivedMessages()).hasSize(1);
    }

    @Test
    void emailFailureDoesNotBlockTelegram() {
        telegramReturns(200);
        // an invalid recipient makes the email step fail fast (before any SMTP call)
        assertThat(postYaml("/api/workflows", yaml("mc-mailfail", "not-an-email")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        String executionId = triggerAndAwaitTerminal("mc-mailfail");

        assertThat(getExecution(executionId).get("status")).isEqualTo("FAILED");
        assertThat(stepByName(executionId, "email_alert").get("status")).isEqualTo("FAILED");
        assertThat(stepByName(executionId, "telegram_alert").get("status")).isEqualTo("SUCCEEDED");

        // telegram still delivered despite the email failure, and SMTP got nothing
        WIRE_MOCK.verify(1, postRequestedFor(urlPathMatching("/bot.*/sendMessage")));
        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();
    }
}
