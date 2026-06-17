package io.potok.recipient;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JDBC repo for the recipient directory. Single index on (chat_id) keeps
 * the upsert hot path cheap; status is filtered in-memory only when small.
 */
@Repository
public class RecipientRepository {

    private final JdbcClient jdbc;
    private final RowMapper<Recipient> rowMapper = RecipientRepository::mapRow;

    public RecipientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Recipient> findByChatId(String chatId) {
        return jdbc.sql("select * from telegram_recipient where chat_id = :chatId")
                .param("chatId", chatId)
                .query(rowMapper)
                .optional();
    }

    public Optional<Recipient> findById(UUID id) {
        return jdbc.sql("select * from telegram_recipient where id = :id")
                .param("id", id)
                .query(rowMapper)
                .optional();
    }

    /**
     * Insert OR refresh display name + last_seen_at. Status comes from the
     * caller because auto-approve toggles routing at first-contact time.
     * @return the post-state row
     */
    public Recipient upsertOnContact(String chatId, String displayName,
                                     Recipient.Status statusOnInsert) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime approvedAt = statusOnInsert == Recipient.Status.APPROVED ? now : null;
        return jdbc.sql("""
                        insert into telegram_recipient
                            (chat_id, display_name, status, approved_at, last_seen_at)
                        values
                            (:chatId, :displayName, :status, :approvedAt, :now)
                        on conflict (chat_id) do update
                        set display_name = excluded.display_name,
                            last_seen_at = :now
                        returning *
                        """)
                .param("chatId", chatId)
                .param("displayName", displayName)
                .param("status", statusOnInsert.name())
                .param("approvedAt", approvedAt)
                .param("now", now)
                .query(rowMapper)
                .single();
    }

    public void updateStatus(UUID id, Recipient.Status status) {
        OffsetDateTime approvedAt = status == Recipient.Status.APPROVED
                ? OffsetDateTime.now() : null;
        jdbc.sql("""
                        update telegram_recipient
                        set status = :status,
                            approved_at = coalesce(:approvedAt, approved_at)
                        where id = :id
                        """)
                .param("id", id)
                .param("status", status.name())
                .param("approvedAt", approvedAt)
                .update();
    }

    public boolean deleteById(UUID id) {
        return jdbc.sql("delete from telegram_recipient where id = :id")
                .param("id", id)
                .update() > 0;
    }

    public List<Recipient> list(Recipient.Status statusFilter, int limit, int offset) {
        if (statusFilter == null) {
            return jdbc.sql("""
                            select * from telegram_recipient
                            order by
                                case status when 'PENDING' then 0 when 'APPROVED' then 1 else 2 end,
                                last_seen_at desc
                            limit :limit offset :offset
                            """)
                    .param("limit", limit)
                    .param("offset", offset)
                    .query(rowMapper)
                    .list();
        }
        return jdbc.sql("""
                        select * from telegram_recipient
                        where status = :status
                        order by last_seen_at desc
                        limit :limit offset :offset
                        """)
                .param("status", statusFilter.name())
                .param("limit", limit)
                .param("offset", offset)
                .query(rowMapper)
                .list();
    }

    public long countByStatus(Recipient.Status status) {
        return jdbc.sql("select count(*) from telegram_recipient where status = :status")
                .param("status", status.name())
                .query(Long.class)
                .single();
    }

    public List<Recipient> listApproved() {
        return jdbc.sql("select * from telegram_recipient where status = 'APPROVED' order by display_name")
                .query(rowMapper)
                .list();
    }

    public Optional<Recipient> findApprovedByIdOrName(String idOrName) {
        // try uuid first; fall back to display_name match.
        try {
            UUID asUuid = UUID.fromString(idOrName);
            return jdbc.sql("select * from telegram_recipient where id = :id and status = 'APPROVED'")
                    .param("id", asUuid)
                    .query(rowMapper)
                    .optional();
        } catch (IllegalArgumentException ignored) {
            return jdbc.sql("""
                            select * from telegram_recipient
                            where status = 'APPROVED'
                              and lower(display_name) = lower(:name)
                            order by last_seen_at desc
                            limit 1
                            """)
                    .param("name", idOrName)
                    .query(rowMapper)
                    .optional();
        }
    }

    private static Recipient mapRow(ResultSet rs, int rowNum) throws SQLException {
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
    }
}
