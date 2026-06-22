package io.potok.action;

/**
 * Effective SMTP config resolved at send time, carrying the plaintext password
 * in memory only (decrypted from the DB row, or taken from env). {@code source}
 * records where it came from so the UI/API can explain precedence.
 */
public record SmtpConfig(
        String host,
        int port,
        String username,
        String password,
        String from,
        boolean starttls,
        boolean auth,
        Source source) {

    public enum Source { DB, ENV, NONE }

    public boolean configured() {
        return source != Source.NONE;
    }

    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }

    /** Sender address: explicit from, else the authenticated username. */
    public String effectiveFrom() {
        return from != null && !from.isBlank() ? from : username;
    }

    public static SmtpConfig none() {
        return new SmtpConfig(null, 587, null, null, null, true, true, Source.NONE);
    }

    /** Build the env-sourced config from the {@code potok.smtp} properties, or NONE. */
    public static SmtpConfig fromEnv(EmailProperties props) {
        if (props == null || !props.isConfigured()) {
            return none();
        }
        return new SmtpConfig(props.host(), props.portOrDefault(), props.username(),
                props.password(), props.fromOrUsername(), props.starttlsOrDefault(),
                props.authOrDefault(), Source.ENV);
    }
}
