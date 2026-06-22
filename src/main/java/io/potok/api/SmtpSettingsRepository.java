package io.potok.api;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Single-row store for the dashboard-managed SMTP config (smtp_config). */
@Repository
public class SmtpSettingsRepository {

    private final JdbcClient jdbc;

    public SmtpSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The stored row; {@code passwordEncrypted} is the AES-GCM ciphertext (never plaintext). */
    public record SmtpRow(String host, Integer port, String username, String fromAddress,
                          boolean starttls, boolean auth, String passwordEncrypted) {
    }

    public Optional<SmtpRow> find() {
        return jdbc.sql("""
                        select host, port, username, from_address, starttls, auth, password_encrypted
                        from smtp_config where id = true
                        """)
                .query((rs, n) -> new SmtpRow(
                        rs.getString("host"),
                        (Integer) rs.getObject("port"),
                        rs.getString("username"),
                        rs.getString("from_address"),
                        rs.getBoolean("starttls"),
                        rs.getBoolean("auth"),
                        rs.getString("password_encrypted")))
                .optional();
    }

    /** Upsert the single row. {@code passwordEncrypted} null clears the stored secret. */
    public void save(String host, Integer port, String username, String fromAddress,
                     boolean starttls, boolean auth, String passwordEncrypted) {
        jdbc.sql("""
                        insert into smtp_config
                            (id, host, port, username, from_address, starttls, auth, password_encrypted, updated_at)
                        values (true, :host, :port, :username, :from, :starttls, :auth, :pw, now())
                        on conflict (id) do update set
                            host = :host, port = :port, username = :username, from_address = :from,
                            starttls = :starttls, auth = :auth, password_encrypted = :pw, updated_at = now()
                        """)
                .param("host", host)
                .param("port", port)
                .param("username", username)
                .param("from", fromAddress)
                .param("starttls", starttls)
                .param("auth", auth)
                .param("pw", passwordEncrypted)
                .update();
    }

    public void delete() {
        jdbc.sql("delete from smtp_config where id = true").update();
    }
}
