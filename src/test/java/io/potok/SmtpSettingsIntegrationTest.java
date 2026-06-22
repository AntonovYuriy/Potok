package io.potok;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Dashboard SMTP settings end-to-end: write-only password stored encrypted,
 * resolution precedence (DB over env), real send via DB config and the test
 * endpoint — all against an embedded GreenMail SMTP with an auth user.
 */
class SmtpSettingsIntegrationTest extends IntegrationTestBase {

    private static final String USER = "smtpuser";
    private static final String PASS = "smtppass";
    private static final GreenMail GREEN_MAIL = new GreenMail(ServerSetup.SMTP.dynamicPort());

    static {
        GREEN_MAIL.start();
        GREEN_MAIL.setUser("smtp@potok.io", USER, PASS);
    }

    @Autowired
    private JdbcClient jdbc;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("potok.secret-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        // env fallback points at the same GreenMail (no auth) so DELETE has somewhere to fall back to
        registry.add("potok.smtp.host", () -> "127.0.0.1");
        registry.add("potok.smtp.port", () -> GREEN_MAIL.getSmtp().getPort());
        registry.add("potok.smtp.from", () -> "env@potok.io");
        registry.add("potok.smtp.auth", () -> false);
        registry.add("potok.smtp.starttls", () -> false);
    }

    @AfterEach
    void cleanup() throws Exception {
        rest.exchange("/api/settings/smtp", HttpMethod.DELETE, HttpEntity.EMPTY, MAP_TYPE);
        GREEN_MAIL.purgeEmailFromAllMailboxes();
    }

    private Map<String, Object> dbBody(String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("host", "127.0.0.1");
        body.put("port", GREEN_MAIL.getSmtp().getPort());
        body.put("username", USER);
        body.put("from", "smtp@potok.io");
        body.put("starttls", false);
        body.put("auth", true);
        if (password != null) {
            body.put("password", password);
        }
        return body;
    }

    private ResponseEntity<Map<String, Object>> put(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/settings/smtp", HttpMethod.PUT,
                new HttpEntity<>(body, headers), MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSmtp() {
        return rest.getForObject("/api/settings/smtp", Map.class);
    }

    @Test
    void putStoresCiphertextAndGetHidesIt() {
        assertThat(put(dbBody(PASS)).getStatusCode()).isEqualTo(HttpStatus.OK);

        // the row holds ciphertext, never the plaintext password
        String stored = jdbc.sql("select password_encrypted from smtp_config where id = true")
                .query(String.class).single();
        assertThat(stored).isNotEqualTo(PASS).doesNotContain(PASS);

        Map<String, Object> view = getSmtp();
        assertThat(view.get("source")).isEqualTo("db");
        assertThat(view.get("password_set")).isEqualTo(true);
        assertThat(view.get("username")).isEqualTo(USER);
        assertThat(view).doesNotContainKey("password"); // write-only — never returned
    }

    @Test
    void putWithoutPasswordKeepsStoredSecret() {
        put(dbBody(PASS));
        // edit a field without re-sending the password
        Map<String, Object> noPassword = dbBody(null);
        noPassword.put("from", "changed@potok.io");
        assertThat(put(noPassword).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> view = getSmtp();
        assertThat(view.get("password_set")).isEqualTo(true);
        assertThat(view.get("from")).isEqualTo("changed@potok.io");
        assertThat(view.get("source")).isEqualTo("db"); // still complete → still DB
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendUsesDbConfig() throws Exception {
        put(dbBody(PASS));

        ResponseEntity<Map<String, Object>> created = postYaml("/api/workflows", """
                name: smtp-db-send
                trigger:
                  webhook: { path: "smtp-db-send" }
                steps:
                  - name: notify
                    action: email
                    with:
                      to: "dest@example.com"
                      subject: "via db"
                      body: "hi"
                """);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String executionId = (String) postJson("/hooks/smtp-db-send", Map.of()).getBody().get("executionId");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(getExecution(executionId).get("status")).isEqualTo("SUCCEEDED"));

        assertThat(GREEN_MAIL.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("dest@example.com");
    }

    @Test
    void deleteFallsBackToEnv() {
        put(dbBody(PASS));
        assertThat(getSmtp().get("source")).isEqualTo("db");

        rest.exchange("/api/settings/smtp", HttpMethod.DELETE, HttpEntity.EMPTY, MAP_TYPE);

        Map<String, Object> view = getSmtp();
        assertThat(view.get("source")).isEqualTo("env");
        assertThat(view.get("configured")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testEndpointOkWithValidConfig() {
        put(dbBody(PASS));

        Map<String, Object> result = rest.postForObject("/api/settings/smtp/test", null, Map.class);

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("error")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testEndpointReportsBadAuth() {
        put(dbBody("wrong-password"));

        Map<String, Object> result = rest.postForObject("/api/settings/smtp/test", null, Map.class);

        assertThat(result.get("ok")).isEqualTo(false);
        String error = String.valueOf(result.get("error"));
        assertThat(error).doesNotContain("wrong-password"); // never leak the secret
        assertThat(error).isNotBlank();
    }
}
