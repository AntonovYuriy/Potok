package io.potok.execution;

import io.potok.common.Json;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DeadLetterRepository {

    private final JdbcClient jdbc;
    private final Json json;
    private final StepExecutionRepository stepExecutions;
    private final RowMapper<DeadLetter> rowMapper;

    public DeadLetterRepository(JdbcClient jdbc, Json json, StepExecutionRepository stepExecutions) {
        this.jdbc = jdbc;
        this.json = json;
        this.stepExecutions = stepExecutions;
        this.rowMapper = this::mapRow;
    }

    public DeadLetter insert(UUID executionId, String stepName, int attempts,
                             String lastError, Map<String, Object> payload) {
        return jdbc.sql("""
                        insert into dead_letter (execution_id, step_name, attempts, last_error, payload)
                        values (:executionId, :stepName, :attempts, :lastError, :payload::jsonb)
                        returning *
                        """)
                .param("executionId", executionId)
                .param("stepName", stepName)
                .param("attempts", attempts)
                .param("lastError", lastError)
                .param("payload", json.write(payload))
                .query(rowMapper)
                .single();
    }

    public List<DeadLetter> page(int page, int size) {
        return jdbc.sql("select * from dead_letter order by created_at desc limit :limit offset :offset")
                .param("limit", size)
                .param("offset", (long) page * size)
                .query(rowMapper)
                .list();
    }

    public long count() {
        return jdbc.sql("select count(*) from dead_letter").query(Long.class).single();
    }

    public Optional<DeadLetter> findById(long id) {
        return jdbc.sql("select * from dead_letter where id = :id")
                .param("id", id)
                .query(rowMapper)
                .optional();
    }

    public boolean delete(long id) {
        return jdbc.sql("delete from dead_letter where id = :id").param("id", id).update() > 0;
    }

    /**
     * Puts a dead job back on the queue: fresh job row (attempts reset), execution
     * and step reopened so the worker pipeline picks them up normally.
     * Dependency-skipped steps downstream of the revived one are forgotten so the
     * DAG can run them after the retry succeeds.
     */
    @Transactional
    public boolean requeue(DeadLetter deadLetter, java.util.Collection<String> downstreamSteps) {
        stepExecutions.resetDependencySkipped(deadLetter.executionId(), downstreamSteps);
        jdbc.sql("""
                        insert into job_queue (execution_id, step_name, run_at)
                        values (:executionId, :stepName, now())
                        """)
                .param("executionId", deadLetter.executionId())
                .param("stepName", deadLetter.stepName())
                .update();
        jdbc.sql("""
                        update workflow_execution
                        set status = 'RUNNING', finished_at = null
                        where id = :executionId
                        """)
                .param("executionId", deadLetter.executionId())
                .update();
        jdbc.sql("""
                        update step_execution
                        set status = 'PENDING', finished_at = null
                        where execution_id = :executionId and step_name = :stepName
                        """)
                .param("executionId", deadLetter.executionId())
                .param("stepName", deadLetter.stepName())
                .update();
        return delete(deadLetter.id());
    }

    private DeadLetter mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DeadLetter(
                rs.getLong("id"),
                rs.getObject("execution_id", UUID.class),
                rs.getString("step_name"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                json.readMap(rs.getString("payload")),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
