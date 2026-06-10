package io.potok.trigger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Multi-instance duplicate-fire protection. Two mechanisms:
 * - pollers: a per-workflow Postgres advisory xact lock; a replica that loses
 *   the race skips the tick (the winner updates poll state in the same tx).
 * - cron: an insert-claim on (workflow, scheduled minute) — works even when
 *   replicas fire seconds apart, which an advisory lock alone wouldn't catch.
 * Single-instance behavior is unchanged (locks are always free).
 */
@Component
public class TriggerLocks {

    private static final int NAMESPACE = "potok-trigger".hashCode();

    private final JdbcClient jdbc;

    public TriggerLocks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside a transaction; the lock releases on commit/rollback. */
    public boolean tryAdvisoryLock(UUID workflowId) {
        return Boolean.TRUE.equals(jdbc
                .sql("select pg_try_advisory_xact_lock(:ns, :key)")
                .param("ns", NAMESPACE)
                .param("key", workflowId.hashCode())
                .query(Boolean.class)
                .single());
    }

    /** @return true if this call won the claim for the workflow's scheduled minute */
    public boolean claimCronFire(UUID workflowId, Instant scheduled) {
        return jdbc.sql("""
                        insert into cron_fire (workflow_id, fire_time)
                        values (:workflowId, :fireTime)
                        on conflict do nothing
                        """)
                .param("workflowId", workflowId)
                .param("fireTime", java.time.OffsetDateTime.ofInstant(
                        scheduled.truncatedTo(ChronoUnit.MINUTES), java.time.ZoneOffset.UTC))
                .update() > 0;
    }
}
