package io.potok.action;

import jakarta.mail.Address;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Properties;

/**
 * Thin SMTP client (plain Jakarta Mail / Angus, no Spring Mail autoconfig)
 * shared by the email action. The effective config is resolved per call via
 * {@link SmtpConfigResolver} — DB-stored settings take precedence over the
 * {@code SMTP_*} env vars (M9), env keeps working unchanged.
 *
 * <p>Connection/read/write timeouts are capped at {@value #TIMEOUT_MS} ms so a
 * stuck SMTP server can never hang a worker thread.
 */
@Component
public class EmailClient {

    static final int TIMEOUT_MS = 10_000;

    private final SmtpConfigResolver resolver;

    public EmailClient(SmtpConfigResolver resolver) {
        this.resolver = resolver;
    }

    public boolean isConfigured() {
        return resolver.resolve().configured();
    }

    public String from() {
        return resolver.resolve().effectiveFrom();
    }

    /** A blank MimeMessage bound to a freshly-configured session. */
    public MimeMessage newMessage() {
        return new MimeMessage(session(resolver.resolve()));
    }

    /**
     * Sends to every recipient and reports per-address outcome where the
     * server exposes it. A partial failure (some addresses rejected) is NOT an
     * exception — it comes back in {@link SendOutcome#failed()}; only a total
     * failure (connect/auth/all-rejected) throws.
     */
    public SendOutcome send(Message message, Address[] recipients) throws MessagingException {
        SmtpConfig config = resolver.resolve();
        Session session = session(config);
        try (Transport transport = session.getTransport("smtp")) {
            connect(transport, config);
            try {
                transport.sendMessage(message, recipients);
                return new SendOutcome(recipients, new Address[0]);
            } catch (SendFailedException e) {
                Address[] sent = e.getValidSentAddresses() == null ? new Address[0] : e.getValidSentAddresses();
                Address[] failed = concat(e.getValidUnsentAddresses(), e.getInvalidAddresses());
                if (sent.length == 0) {
                    throw e; // nobody got it — surface as a hard failure
                }
                return new SendOutcome(sent, failed);
            }
        }
    }

    /**
     * Connects + authenticates against the given config WITHOUT sending — used
     * by the "send test" button. Throws on any failure (caller maps to a safe
     * message). Never logs the password.
     */
    public void verify(SmtpConfig config) throws MessagingException {
        Session session = session(config);
        try (Transport transport = session.getTransport("smtp")) {
            connect(transport, config);
        }
    }

    private void connect(Transport transport, SmtpConfig config) throws MessagingException {
        if (config.auth()) {
            transport.connect(config.host(), config.port(), config.username(), config.password());
        } else {
            transport.connect();
        }
    }

    private Session session(SmtpConfig config) {
        Properties props = new Properties();
        props.put("mail.smtp.host", String.valueOf(config.host()));
        props.put("mail.smtp.port", String.valueOf(config.port()));
        props.put("mail.smtp.auth", String.valueOf(config.auth()));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.starttls()));
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
        if (config.auth()) {
            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(config.username(), config.password());
                }
            });
        }
        return Session.getInstance(props);
    }

    private static Address[] concat(Address[] a, Address[] b) {
        Address[] left = a == null ? new Address[0] : a;
        Address[] right = b == null ? new Address[0] : b;
        Address[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    /** Per-send result: addresses the server accepted vs. rejected. */
    public record SendOutcome(Address[] sent, Address[] failed) {
    }
}
