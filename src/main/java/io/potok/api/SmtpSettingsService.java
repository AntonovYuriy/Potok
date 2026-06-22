package io.potok.api;

import io.potok.action.EmailProperties;
import io.potok.action.SmtpConfig;
import io.potok.action.SmtpConfigResolver;
import io.potok.api.SmtpSettingsRepository.SmtpRow;
import io.potok.common.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves the effective SMTP config and manages the dashboard-stored one.
 *
 * <p>Precedence (backward compatible): a complete DB row wins; otherwise the
 * {@code SMTP_*} env vars (M8 behaviour); otherwise "not configured". The
 * password is decrypted only here, in memory, at resolve time — never logged,
 * never returned by the API.
 */
@Service
public class SmtpSettingsService implements SmtpConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(SmtpSettingsService.class);
    private static final int DEFAULT_PORT = 587;

    private final SmtpSettingsRepository repo;
    private final SecretCipher cipher;
    private final EmailProperties env;

    public SmtpSettingsService(SmtpSettingsRepository repo, SecretCipher cipher, EmailProperties env) {
        this.repo = repo;
        this.cipher = cipher;
        this.env = env;
    }

    @Override
    public SmtpConfig resolve() {
        SmtpConfig fromDb = resolveDb().orElse(null);
        if (fromDb != null) {
            return fromDb;
        }
        return SmtpConfig.fromEnv(env); // ENV or NONE
    }

    private Optional<SmtpConfig> resolveDb() {
        Optional<SmtpRow> maybe = repo.find();
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        SmtpRow row = maybe.get();
        if (row.host() == null || row.host().isBlank()) {
            return Optional.empty();
        }
        boolean auth = row.auth();
        String password = null;
        if (auth) {
            if (row.passwordEncrypted() == null || row.passwordEncrypted().isBlank()) {
                return Optional.empty(); // incomplete — fall back to env
            }
            if (!cipher.isEnabled()) {
                // key removed after storing: can't decrypt, don't crash sends
                log.warn("smtp_config has a stored password but POTOK_SECRET_KEY is unset; "
                        + "falling back to env SMTP config");
                return Optional.empty();
            }
            password = cipher.decrypt(row.passwordEncrypted());
        }
        int port = row.port() == null ? DEFAULT_PORT : row.port();
        String from = row.fromAddress() != null && !row.fromAddress().isBlank()
                ? row.fromAddress() : row.username();
        return Optional.of(new SmtpConfig(row.host(), port, row.username(), password, from,
                row.starttls(), auth, SmtpConfig.Source.DB));
    }

    /** Non-secret view for the dashboard GET — never carries the password. */
    public SmtpView view() {
        SmtpConfig effective = resolve();
        Optional<SmtpRow> row = repo.find();
        if (effective.source() == SmtpConfig.Source.DB && row.isPresent()) {
            SmtpRow r = row.get();
            return new SmtpView(r.host(), r.port() == null ? DEFAULT_PORT : r.port(), r.username(),
                    r.fromAddress(), r.starttls(), r.auth(), true,
                    r.passwordEncrypted() != null && !r.passwordEncrypted().isBlank(), "db");
        }
        if (effective.source() == SmtpConfig.Source.ENV) {
            return new SmtpView(env.host(), env.portOrDefault(), env.username(),
                    env.from(), env.starttlsOrDefault(), env.authOrDefault(), true,
                    env.password() != null && !env.password().isBlank(), "env");
        }
        return new SmtpView(null, DEFAULT_PORT, null, null, true, true, false, false, "none");
    }

    /**
     * Stores the dashboard SMTP config. When {@code password} is null/blank the
     * existing stored secret is preserved (edit other fields without re-entering
     * it). A provided password requires {@code POTOK_SECRET_KEY}.
     */
    public void save(SmtpUpdate update) {
        String encrypted;
        if (update.password() != null && !update.password().isBlank()) {
            if (!cipher.isEnabled()) {
                throw new SecretKeyMissingException();
            }
            encrypted = cipher.encrypt(update.password());
        } else {
            encrypted = repo.find().map(SmtpRow::passwordEncrypted).orElse(null);
        }
        repo.save(blankToNull(update.host()), update.port(), blankToNull(update.username()),
                blankToNull(update.from()),
                update.starttls() == null || update.starttls(),
                update.auth() == null || update.auth(),
                encrypted);
    }

    public void delete() {
        repo.delete();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Thrown when a password is submitted but no encryption key is configured. */
    public static class SecretKeyMissingException extends RuntimeException {
        public SecretKeyMissingException() {
            super("set POTOK_SECRET_KEY to store secrets");
        }
    }

    /** Non-secret SMTP view returned by the API. */
    public record SmtpView(String host, int port, String username, String from,
                           boolean starttls, boolean auth, boolean configured,
                           @com.fasterxml.jackson.annotation.JsonProperty("password_set")
                           boolean passwordSet, String source) {
    }

    /** PUT payload; {@code password} optional (omit to keep the stored one). */
    public record SmtpUpdate(String host, Integer port, String username, String from,
                             Boolean starttls, Boolean auth, String password) {
    }
}
