package io.potok.api;

import io.potok.action.EmailClient;
import io.potok.action.SmtpConfig;
import io.potok.api.SmtpSettingsService.SmtpUpdate;
import io.potok.api.SmtpSettingsService.SmtpView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Dashboard SMTP settings (M9). Write-only password: it can be set/updated via
 * PUT but is NEVER returned by GET or any error. Token-authed like all
 * {@code /api/**}; any valid token may manage it (consistent with the other
 * settings). The "test" endpoint connects+authenticates only, sending nothing.
 */
@RestController
@RequestMapping("/api/settings/smtp")
public class SmtpSettingsController {

    private final SmtpSettingsService smtp;
    private final EmailClient email;

    public SmtpSettingsController(SmtpSettingsService smtp, EmailClient email) {
        this.smtp = smtp;
        this.email = email;
    }

    @GetMapping
    public SmtpView get() {
        return smtp.view();
    }

    @PutMapping
    public ResponseEntity<SmtpView> put(@RequestBody SmtpUpdate body) {
        smtp.save(body == null ? new SmtpUpdate(null, null, null, null, null, null, null) : body);
        return ResponseEntity.ok(smtp.view());
    }

    @DeleteMapping
    public ResponseEntity<SmtpView> delete() {
        smtp.delete();
        return ResponseEntity.ok(smtp.view());
    }

    @PostMapping("/test")
    public TestResult test() {
        SmtpConfig config = smtp.resolve();
        if (!config.configured()) {
            return new TestResult(false, "SMTP is not configured");
        }
        try {
            email.verify(config);
            return new TestResult(true, null);
        } catch (Exception e) {
            return new TestResult(false, safeMessage(e));
        }
    }

    /** Maps an SMTP failure to a category — never echoes the password or raw secret. */
    private static String safeMessage(Exception e) {
        String text = (e.getMessage() == null ? "" : e.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("authentication") || text.contains("username and password")
                || text.contains("535") || text.contains("auth")) {
            return "authentication failed — check the username and app password";
        }
        if (text.contains("timed out") || text.contains("timeout")) {
            return "connection timed out — check the host and port";
        }
        if (text.contains("connect") || text.contains("unknownhost") || text.contains("refused")) {
            return "could not connect — check the host and port";
        }
        if (text.contains("starttls") || text.contains("ssl") || text.contains("tls")) {
            return "TLS negotiation failed — check the STARTTLS setting";
        }
        return "SMTP test failed";
    }

    public record TestResult(boolean ok, String error) {
    }
}
