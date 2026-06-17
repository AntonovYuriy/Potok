package io.potok.api;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Tiny k/v setting store. jsonb so future settings can carry shapes without
 * a migration; for now only boolean telegram_auto_approve is read.
 */
@Repository
public class SettingsRepository {

    private final JdbcClient jdbc;

    public SettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Optional<String> raw = jdbc.sql("select value::text from setting where key = :key")
                .param("key", key)
                .query(String.class)
                .optional();
        return raw.map(v -> "true".equalsIgnoreCase(v.trim())).orElse(defaultValue);
    }

    public void setBoolean(String key, boolean value) {
        jdbc.sql("""
                        insert into setting (key, value, updated_at)
                        values (:key, to_jsonb(:value), now())
                        on conflict (key) do update
                        set value = to_jsonb(:value), updated_at = now()
                        """)
                .param("key", key)
                .param("value", value)
                .update();
    }
}
