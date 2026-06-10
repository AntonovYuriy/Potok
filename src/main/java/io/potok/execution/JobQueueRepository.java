package io.potok.execution;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Postgres-backed job queue. Claiming uses SELECT ... FOR UPDATE SKIP LOCKED
 * so concurrent workers never grab the same row; the claim sets locked_until
 * as a lease, which doubles as crash recovery — expired leases make the row
 * pollable again without any startup sweep.
 */
@Repository
public class JobQueueRepository {

    private final JdbcClient jdbc;
    private final RowMapper<QueuedJob> rowMapper = JobQueueRepository::mapRow;

    public JobQueueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(UUID executionId, String stepName, Instant runAt) {
        jdbc.sql("""
                        insert into job_queue (execution_id, step_name, run_at)
                        values (:executionId, :stepName, :runAt)
                        """)
                .param("executionId", executionId)
                .param("stepName", stepName)
                .param("runAt", OffsetDateTime.ofInstant(runAt, java.time.ZoneOffset.UTC))
                .update();
    }

    /** Claims the next due job: locks the row and leases it for {@code lockTimeout}. */
    @Transactional
    public Optional<QueuedJob> pollAndLock(Duration lockTimeout) {
        return jdbc.sql("""
                        with next_job as (
                            select id from job_queue
                            where run_at <= now()
                              and (locked_until is null or locked_until < now())
                            order by run_at
                            limit 1
                            for update skip locked
                        )
                        update job_queue
                        set locked_until = now() + :lockTimeout::interval
                        from next_job
                        where job_queue.id = next_job.id
                        returning job_queue.*
                        """)
                .param("lockTimeout", lockTimeout.toSeconds() + " seconds")
                .query(rowMapper)
                .optional();
    }

    public void delete(long jobId) {
        jdbc.sql("delete from job_queue where id = :id").param("id", jobId).update();
    }

    /** Drops the lease so another worker/instance can claim the job immediately (graceful shutdown). */
    public void releaseLock(long jobId) {
        jdbc.sql("update job_queue set locked_until = now() where id = :id")
                .param("id", jobId)
                .update();
    }

    /** Releases the job for a retry: bumps attempts, clears the lease, defers to {@code runAt}. */
    public void scheduleRetry(long jobId, Instant runAt) {
        jdbc.sql("""
                        update job_queue
                        set attempts = attempts + 1, locked_until = null, run_at = :runAt
                        where id = :id
                        """)
                .param("id", jobId)
                .param("runAt", OffsetDateTime.ofInstant(runAt, java.time.ZoneOffset.UTC))
                .update();
    }

    private static QueuedJob mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new QueuedJob(
                rs.getLong("id"),
                rs.getObject("execution_id", UUID.class),
                rs.getString("step_name"),
                rs.getObject("run_at", OffsetDateTime.class),
                rs.getInt("attempts"));
    }
}
