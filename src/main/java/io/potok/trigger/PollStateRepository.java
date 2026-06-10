package io.potok.trigger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class PollStateRepository {

    public record PollState(String lastHash, Boolean lastCondition) {
    }

    private final JdbcClient jdbc;

    public PollStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PollState> find(UUID workflowId) {
        return jdbc.sql("select last_hash, last_condition from poll_state where workflow_id = :id")
                .param("id", workflowId)
                .query((rs, n) -> new PollState(
                        rs.getString("last_hash"),
                        rs.getObject("last_condition", Boolean.class)))
                .optional();
    }

    public void upsert(UUID workflowId, String hash, Boolean condition) {
        jdbc.sql("""
                        insert into poll_state (workflow_id, last_hash, last_condition, last_polled_at)
                        values (:id, :hash, :condition, now())
                        on conflict (workflow_id) do update
                        set last_hash = :hash, last_condition = :condition, last_polled_at = now()
                        """)
                .param("id", workflowId)
                .param("hash", hash)
                .param("condition", condition)
                .update();
    }

    /** @return true if this item was not seen before (caller should fire) */
    public boolean markSeen(UUID workflowId, String itemId) {
        return jdbc.sql("""
                        insert into rss_seen (workflow_id, item_id)
                        values (:id, :itemId)
                        on conflict do nothing
                        """)
                .param("id", workflowId)
                .param("itemId", itemId)
                .update() > 0;
    }

    /** First-poll marker for rss: any state row means we have polled before. */
    public boolean hasPolledBefore(UUID workflowId) {
        return jdbc.sql("select count(*) from poll_state where workflow_id = :id")
                .param("id", workflowId)
                .query(Long.class)
                .single() > 0;
    }

    public void touch(UUID workflowId) {
        upsert(workflowId, null, null);
    }
}
