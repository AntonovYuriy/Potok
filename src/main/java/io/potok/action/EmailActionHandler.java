package io.potok.action;

import io.potok.action.EmailClient.SendOutcome;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sends one email via SMTP (Jakarta Mail, no SDK). Provider-agnostic — point
 * {@code SMTP_*} at Gmail, Brevo, SendGrid or any submission server.
 *
 * <p>{@code with} params:
 * <ul>
 *   <li>{@code to} — string OR list of addresses (required);</li>
 *   <li>{@code cc}, {@code bcc} — optional string/list;</li>
 *   <li>{@code subject} — required, templated;</li>
 *   <li>{@code body} — required, templated; plain text unless {@code html: true}.</li>
 * </ul>
 * Addresses are validated, de-duplicated (case-insensitive) and capped at
 * {@value #MAX_RECIPIENTS} across To+Cc+Bcc. One send fans out to all
 * recipients; the step fails only when the whole send fails — a server that
 * rejects some addresses still succeeds, reporting them under {@code failed}.
 */
@Component
public class EmailActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(EmailActionHandler.class);

    static final int MAX_RECIPIENTS = 50;
    static final int MAX_SUBJECT_CHARS = 1_000;
    static final int MAX_BODY_CHARS = 100_000;

    private final EmailClient email;

    public EmailActionHandler(EmailClient email) {
        this.email = email;
    }

    @Override
    public String type() {
        return "email";
    }

    @Override
    public StepResult execute(StepContext ctx) {
        if (!email.isConfigured()) {
            return StepResult.fail("email channel not configured — set SMTP_* env "
                    + "(SMTP_HOST, SMTP_USERNAME, SMTP_PASSWORD) or remove the email step");
        }

        String subject;
        String body;
        try {
            subject = ctx.requireString("subject");
            body = ctx.requireString("body");
        } catch (IllegalArgumentException e) {
            return StepResult.fail(e.getMessage());
        }
        if (subject.length() > MAX_SUBJECT_CHARS) {
            return StepResult.fail("email subject exceeds " + MAX_SUBJECT_CHARS + " characters");
        }
        if (body.length() > MAX_BODY_CHARS) {
            return StepResult.fail("email body exceeds " + MAX_BODY_CHARS + " characters");
        }
        boolean html = asBoolean(ctx.with().get("html"));

        List<String> to;
        List<String> cc;
        List<String> bcc;
        try {
            to = addresses(ctx.with().get("to"), "to");
            cc = addresses(ctx.with().get("cc"), "cc");
            bcc = addresses(ctx.with().get("bcc"), "bcc");
        } catch (IllegalArgumentException e) {
            return StepResult.fail(e.getMessage());
        }
        if (to.isEmpty()) {
            return StepResult.fail("email step needs at least one 'to' address");
        }
        List<String> all = dedupe(concat(to, cc, bcc));
        if (all.size() > MAX_RECIPIENTS) {
            return StepResult.fail("email step addresses " + all.size()
                    + " recipients; the cap is " + MAX_RECIPIENTS);
        }

        try {
            MimeMessage message = email.newMessage();
            message.setFrom(new InternetAddress(email.from()));
            message.setRecipients(Message.RecipientType.TO, toAddressArray(to));
            if (!cc.isEmpty()) {
                message.setRecipients(Message.RecipientType.CC, toAddressArray(cc));
            }
            if (!bcc.isEmpty()) {
                message.setRecipients(Message.RecipientType.BCC, toAddressArray(bcc));
            }
            message.setSubject(subject, "UTF-8");
            if (html) {
                message.setContent(body, "text/html; charset=UTF-8");
            } else {
                message.setText(body, "UTF-8");
            }

            SendOutcome outcome = email.send(message, toAddressArray(all));
            List<String> failed = stringify(outcome.failed());
            log.info("email_sent recipients={} sent={} failed={} html={}",
                    all.size(), outcome.sent().length, failed.size(), html);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("sent_count", outcome.sent().length);
            output.put("failed_count", failed.size());
            output.put("recipients", all);
            if (!failed.isEmpty()) {
                output.put("failed", failed);
            }
            return StepResult.ok(output);
        } catch (AddressException e) {
            return StepResult.fail("invalid email address: " + e.getMessage());
        } catch (Exception e) {
            return StepResult.fail("email send failed: " + io.potok.common.Errors.describe(e));
        }
    }

    /** Coerce a {@code with} value (string or list) into validated addresses; null/blank → empty. */
    private static List<String> addresses(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        List<String> raw = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                splitInto(item == null ? null : item.toString(), raw);
            }
        } else {
            splitInto(value.toString(), raw);
        }
        List<String> valid = new ArrayList<>();
        for (String candidate : raw) {
            try {
                new InternetAddress(candidate, true).validate();
            } catch (AddressException e) {
                throw new IllegalArgumentException(
                        "'" + field + "' has an invalid email address: " + candidate);
            }
            valid.add(candidate);
        }
        return valid;
    }

    /** Friendly: a single string may carry several comma/semicolon-separated addresses. */
    private static void splitInto(String value, List<String> out) {
        if (value == null) {
            return;
        }
        for (String part : value.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
    }

    private static List<String> concat(List<String> a, List<String> b, List<String> c) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        out.addAll(c);
        return out;
    }

    private static List<String> dedupe(List<String> addresses) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String address : addresses) {
            if (seen.add(address.toLowerCase(java.util.Locale.ROOT))) {
                out.add(address);
            }
        }
        return out;
    }

    private static Address[] toAddressArray(List<String> addresses) throws AddressException {
        Address[] out = new Address[addresses.size()];
        for (int i = 0; i < addresses.size(); i++) {
            out[i] = new InternetAddress(addresses.get(i));
        }
        return out;
    }

    private static List<String> stringify(Address[] addresses) {
        List<String> out = new ArrayList<>();
        for (Address address : addresses) {
            out.add(address.toString());
        }
        return out;
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(value.toString().trim());
    }
}
