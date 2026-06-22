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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full path for the email channel: workflow created via API, fired via webhook,
 * the email step runs through the queue and a real message lands in an embedded
 * SMTP server — and preview of the same step sends nothing.
 */
class EmailE2EIntegrationTest extends IntegrationTestBase {

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
    void reset() {
        GREEN_MAIL.reset();
    }

    private String yaml() {
        return """
                name: email-e2e
                trigger:
                  webhook: { path: "email-e2e" }
                steps:
                  - name: notify
                    action: email
                    with:
                      to: "ops@example.com"
                      subject: "Event: {{ trigger.kind }}"
                      body: "Got {{ trigger.kind }} from {{ trigger.who }}"
                """;
    }

    @Test
    @SuppressWarnings("unchecked")
    void webhookTriggeredEmailStepReallySends() throws Exception {
        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", yaml());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> triggered =
                postJson("/hooks/email-e2e", Map.of("kind", "deploy", "who", "ci"));
        assertThat(triggered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String executionId = (String) triggered.getBody().get("executionId");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        assertThat(GREEN_MAIL.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] messages = GREEN_MAIL.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getSubject()).isEqualTo("Event: deploy");
        assertThat(messages[0].getAllRecipients()[0].toString()).isEqualTo("ops@example.com");
        assertThat(messages[0].getContent().toString().trim()).isEqualTo("Got deploy from ci");

        Map<String, Object> execution = getExecution(executionId);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) execution.get("steps");
        Map<String, Object> output = (Map<String, Object>) steps.get(0).get("output");
        assertThat(output.get("sent_count")).isEqualTo(1);
    }

    @Test
    void previewDoesNotSend() {
        ResponseEntity<Map<String, Object>> preview = postYaml("/api/preview", yaml());
        assertThat(preview.getStatusCode().is2xxSuccessful()).isTrue();

        // preview simulates — the SMTP server must have received nothing
        assertThat(GREEN_MAIL.waitForIncomingEmail(1000, 1)).isFalse();
        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();
    }
}
