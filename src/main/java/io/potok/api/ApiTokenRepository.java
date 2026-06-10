package io.potok.api;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class ApiTokenRepository {

    public record TokenMeta(UUID id, String name, OffsetDateTime createdAt,
                            OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {
    }

    private final JdbcClient jdbc;
    private final RowMapper<TokenMeta> rowMapper = ApiTokenRepository::mapRow;

    public ApiTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TokenMeta insert(String name, String tokenHash) {
        return jdbc.sql("""
                        insert into api_token (name, token_hash)
                        values (:name, :hash)
                        returning id, name, created_at, last_used_at, revoked_at
                        """)
                .param("name", name)
                .param("hash", tokenHash)
                .query(rowMapper)
                .single();
    }

    public List<TokenMeta> list() {
        return jdbc.sql("select id, name, created_at, last_used_at, revoked_at from api_token order by created_at")
                .query(rowMapper)
                .list();
    }

    /** Validates AND stamps last_used_at in one statement; only active tokens match. */
    public boolean useActiveToken(String tokenHash) {
        return jdbc.sql("""
                        update api_token set last_used_at = now()
                        where token_hash = :hash and revoked_at is null
                        """)
                .param("hash", tokenHash)
                .update() > 0;
    }

    public boolean revoke(UUID id) {
        return jdbc.sql("update api_token set revoked_at = now() where id = :id and revoked_at is null")
                .param("id", id)
                .update() > 0;
    }

    private static TokenMeta mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TokenMeta(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("last_used_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class));
    }
}
