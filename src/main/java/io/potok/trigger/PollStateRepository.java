package io.potok.trigger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class PollStateRepository {

    public record PollState(String lastHash, Boolean lastCondition, String etag, String lastModified) {
    }

    private final JdbcClient jdbc;

    public PollStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PollState> find(UUID workflowId) {
        return jdbc.sql("""
                        select last_hash, last_condition, etag, last_modified
                        from poll_state where workflow_id = :id
                        """)
                .param("id", workflowId)
                .query((rs, n) -> new PollState(
                        rs.getString("last_hash"),
                        rs.getObject("last_condition", Boolean.class),
                        rs.getString("etag"),
                        rs.getString("last_modified")))
                .optional();
    }

    public void upsert(UUID workflowId, String hash, Boolean condition, String etag, String lastModified) {
        jdbc.sql("""
                        insert into poll_state (workflow_id, last_hash, last_condition, etag, last_modified, last_polled_at)
                        values (:id, :hash, :condition, :etag, :lastModified, now())
                        on conflict (workflow_id) do update
                        set last_hash = :hash, last_condition = :condition,
                            etag = :etag, last_modified = :lastModified, last_polled_at = now()
                        """)
                .param("id", workflowId)
                .param("hash", hash)
                .param("condition", condition)
                .param("etag", etag)
                .param("lastModified", lastModified)
                .update();
    }

    /**
     * Marks every item as seen in ONE statement and returns the ids that were
     * not seen before (the caller fires for those). Replaces the per-item
     * insert loop — a 20-item feed is 1 round-trip instead of 20.
     */
    public Set<String> markSeenBatch(UUID workflowId, Collection<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Set.of();
        }
        var spec = jdbc.sql(buildBatchSql(itemIds.size()));
        spec = spec.param("id", workflowId);
        int i = 0;
        for (String itemId : itemIds) {
            spec = spec.param("item" + i++, itemId);
        }
        return new LinkedHashSet<>(spec.query(String.class).list());
    }

    private static String buildBatchSql(int count) {
        StringBuilder sql = new StringBuilder("insert into rss_seen (workflow_id, item_id) values ");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(:id, :item").append(i).append(')');
        }
        return sql.append(" on conflict do nothing returning item_id").toString();
    }

}
