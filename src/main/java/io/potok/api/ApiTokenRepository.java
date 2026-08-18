package io.potok.api;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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

    /**
     * The last_used_at stamp is informational (token list UI); writing it on
     * every request turned each dashboard poll into WAL traffic. One write per
     * token per this window is plenty.
     */
    static final java.time.Duration LAST_USED_THROTTLE = java.time.Duration.ofMinutes(1);

    /**
     * Validates the token (read) and stamps last_used_at at most once per
     * {@link #LAST_USED_THROTTLE}; only active tokens match.
     */
    public boolean useActiveToken(String tokenHash) {
        Optional<OffsetDateTime> active = jdbc.sql("""
                        select last_used_at from api_token
                        where token_hash = :hash and revoked_at is null
                        """)
                .param("hash", tokenHash)
                .query((rs, n) -> Optional.ofNullable(rs.getObject("last_used_at", OffsetDateTime.class)))
                .optional()
                .map(lastUsed -> lastUsed.orElse(OffsetDateTime.MIN));
        if (active.isEmpty()) {
            return false;
        }
        if (active.get().isBefore(OffsetDateTime.now().minus(LAST_USED_THROTTLE))) {
            jdbc.sql("update api_token set last_used_at = now() where token_hash = :hash")
                    .param("hash", tokenHash)
                    .update();
        }
        return true;
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
