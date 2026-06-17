package io.potok.subscription;

import io.potok.recipient.Recipient;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Plain JDBC repo for the workflow ↔ recipient link. Idempotent writes
 * (insert/delete) so re-tapping a button never crashes; queries always join
 * recipient status so revoked chats vanish from {@link #listApprovedSubscribers}
 * without a separate cleanup.
 */
@Repository
public class SubscriptionRepository {

    private final JdbcClient jdbc;

    public SubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true when this call inserted a new row (false on duplicate). */
    public boolean insert(UUID workflowId, UUID recipientId) {
        return jdbc.sql("""
                        insert into workflow_subscription (workflow_id, recipient_id)
                        values (:workflowId, :recipientId)
                        on conflict do nothing
                        """)
                .param("workflowId", workflowId)
                .param("recipientId", recipientId)
                .update() > 0;
    }

    /** @return true when this call removed a row (false when nothing to delete). */
    public boolean delete(UUID workflowId, UUID recipientId) {
        return jdbc.sql("""
                        delete from workflow_subscription
                        where workflow_id = :workflowId and recipient_id = :recipientId
                        """)
                .param("workflowId", workflowId)
                .param("recipientId", recipientId)
                .update() > 0;
    }

    public boolean exists(UUID workflowId, UUID recipientId) {
        return jdbc.sql("""
                        select 1 from workflow_subscription
                        where workflow_id = :workflowId and recipient_id = :recipientId
                        """)
                .param("workflowId", workflowId)
                .param("recipientId", recipientId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public long countSubscribers(UUID workflowId) {
        return jdbc.sql("""
                        select count(*) from workflow_subscription ws
                        join telegram_recipient r on r.id = ws.recipient_id
                        where ws.workflow_id = :workflowId and r.status = 'APPROVED'
                        """)
                .param("workflowId", workflowId)
                .query(Long.class)
                .single();
    }

    private static final RowMapper<Recipient> RECIPIENT_ROW = (rs, n) -> {
        OffsetDateTime approvedAt = rs.getObject("approved_at", OffsetDateTime.class);
        return new Recipient(
                rs.getObject("id", UUID.class),
                rs.getString("chat_id"),
                rs.getString("display_name"),
                Recipient.Status.valueOf(rs.getString("status")),
                rs.getString("source"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                approvedAt == null ? null : approvedAt.toInstant(),
                rs.getObject("last_seen_at", OffsetDateTime.class).toInstant());
    };

    /** Used by the telegram action's fan-out for {@code to: subscribers}. */
    public List<Recipient> listApprovedSubscribers(UUID workflowId) {
        return jdbc.sql("""
                        select r.* from workflow_subscription ws
                        join telegram_recipient r on r.id = ws.recipient_id
                        where ws.workflow_id = :workflowId and r.status = 'APPROVED'
                        order by r.display_name
                        """)
                .param("workflowId", workflowId)
                .query(RECIPIENT_ROW)
                .list();
    }
}
