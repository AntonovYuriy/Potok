package io.potok.action;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider-agnostic SMTP config. All optional — when {@code host} is blank the
 * email action fails the step gracefully (mirrors telegram without a token).
 * {@code from} defaults to {@code username}; STARTTLS and AUTH default on, which
 * fits Gmail/Brevo/SendGrid submission on port 587.
 */
@ConfigurationProperties(prefix = "potok.smtp")
public record EmailProperties(
        String host,
        Integer port,
        String username,
        String password,
        String from,
        Boolean starttls,
        Boolean auth) {

    public boolean isConfigured() {
        return host != null && !host.isBlank();
    }

    public int portOrDefault() {
        return port == null ? 587 : port;
    }

    public boolean starttlsOrDefault() {
        return starttls == null || starttls;
    }

    public boolean authOrDefault() {
        return auth == null || auth;
    }

    /** Sender address: explicit {@code SMTP_FROM} or the authenticated username. */
    public String fromOrUsername() {
        return from != null && !from.isBlank() ? from : username;
    }
}
