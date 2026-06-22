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
 * shared by the email action. Config comes from {@code SMTP_*} env only.
 *
 * <p>Connection/read/write timeouts are capped at {@value #TIMEOUT_MS} ms so a
 * stuck SMTP server can never hang a worker thread.
 */
@Component
public class EmailClient {

    private static final int TIMEOUT_MS = 10_000;

    private final EmailProperties properties;

    public EmailClient(EmailProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public String from() {
        return properties.fromOrUsername();
    }

    /** A blank MimeMessage bound to a freshly-configured session. */
    public MimeMessage newMessage() {
        return new MimeMessage(session());
    }

    /**
     * Sends to every recipient and reports per-address outcome where the
     * server exposes it. A partial failure (some addresses rejected) is NOT an
     * exception — it comes back in {@link SendOutcome#failed()}; only a total
     * failure (connect/auth/all-rejected) throws.
     */
    public SendOutcome send(Message message, Address[] recipients) throws MessagingException {
        Session session = session();
        try (Transport transport = session.getTransport("smtp")) {
            if (properties.authOrDefault()) {
                transport.connect(properties.host(), properties.portOrDefault(),
                        properties.username(), properties.password());
            } else {
                transport.connect();
            }
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

    private Session session() {
        Properties props = new Properties();
        props.put("mail.smtp.host", properties.host());
        props.put("mail.smtp.port", String.valueOf(properties.portOrDefault()));
        props.put("mail.smtp.auth", String.valueOf(properties.authOrDefault()));
        props.put("mail.smtp.starttls.enable", String.valueOf(properties.starttlsOrDefault()));
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
        if (properties.authOrDefault()) {
            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(properties.username(), properties.password());
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
