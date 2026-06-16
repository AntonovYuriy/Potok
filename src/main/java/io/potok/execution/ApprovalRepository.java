package io.potok.execution;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Approvals: plaintext tokens live only in the outgoing message links; this
 * table stores SHA-256 hashes. {@code decide} flips a row exactly once —
 * the conditional update is the one-time-use guarantee.
 */
@Repository
public class ApprovalRepository {

    private final JdbcClient jdbc;
    private final RowMapper<Approval> rowMapper = ApprovalRepository::mapRow;

    public ApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A retry of the asking step replaces the previous (undelivered) tokens. */
    public void upsert(UUID executionId, String stepName, String workflowName,
                       String approveHash, String denyHash, Instant expiresAt) {
        jdbc.sql("""
                        insert into approval (execution_id, step_name, workflow_name,
                                              approve_hash, deny_hash, expires_at)
                        values (:executionId, :stepName, :workflowName, :approveHash, :denyHash, :expiresAt)
                        on conflict (execution_id, step_name) do update
                        set approve_hash = :approveHash, deny_hash = :denyHash,
                            expires_at = :expiresAt
                        where approval.decided_at is null
                        """)
                .param("executionId", executionId)
                .param("stepName", stepName)
                .param("workflowName", workflowName)
                .param("approveHash", approveHash)
                .param("denyHash", denyHash)
                .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .update();
    }

    /** @return the approval plus whether the hash matched the approve (true) or deny (false) link */
    public Optional<TokenMatch> findByTokenHash(String hash) {
        return jdbc.sql("""
                        select *, (approve_hash = :hash) as is_approve
                        from approval
                        where approve_hash = :hash or deny_hash = :hash
                        """)
                .param("hash", hash)
                .query((rs, n) -> new TokenMatch(mapRow(rs, n), rs.getBoolean("is_approve")))
                .optional();
    }

    public Optional<Approval> find(UUID executionId, String stepName) {
        return jdbc.sql("select * from approval where execution_id = :executionId and step_name = :stepName")
                .param("executionId", executionId)
                .param("stepName", stepName)
                .query(rowMapper)
                .optional();
    }

    /** Remembers which Telegram message carries the buttons so a decision can edit it. */
    public void attachMessage(UUID id, String chatId, Long messageId, String question) {
        jdbc.sql("""
                        update approval
                        set chat_id = :chatId, message_id = :messageId, question = :question
                        where id = :id
                        """)
                .param("id", id)
                .param("chatId", chatId)
                .param("messageId", messageId)
                .param("question", question)
                .update();
    }

    public Optional<Approval> findById(UUID id) {
        return jdbc.sql("select * from approval where id = :id")
                .param("id", id)
                .query(rowMapper)
                .optional();
    }

    /** One-time use: only flips an undecided row. @return true when THIS call decided it. */
    public boolean decide(UUID id, String decision) {
        return jdbc.sql("""
                        update approval
                        set decided_at = now(), decision = :decision
                        where id = :id and decided_at is null
                        """)
                .param("id", id)
                .param("decision", decision)
                .update() > 0;
    }

    public record TokenMatch(Approval approval, boolean isApprove) {
    }

    private static Approval mapRow(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
        long rawMessageId = rs.getLong("message_id");
        Long messageId = rs.wasNull() ? null : rawMessageId;
        return new Approval(
                rs.getObject("id", UUID.class),
                rs.getObject("execution_id", UUID.class),
                rs.getString("step_name"),
                rs.getString("workflow_name"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getString("decision"),
                rs.getString("chat_id"),
                messageId,
                rs.getString("question"));
    }
}
