package io.potok.execution;

import io.potok.common.Json;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class StepExecutionRepository {

    private final JdbcClient jdbc;
    private final Json json;
    private final RowMapper<StepExecution> rowMapper;

    public StepExecutionRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = this::mapRow;
    }

    public Optional<StepExecution> find(UUID executionId, String stepName) {
        return jdbc.sql("select * from step_execution where execution_id = :executionId and step_name = :stepName")
                .param("executionId", executionId)
                .param("stepName", stepName)
                .query(rowMapper)
                .optional();
    }

    public List<StepExecution> findByExecution(UUID executionId) {
        return jdbc.sql("select * from step_execution where execution_id = :executionId order by created_at")
                .param("executionId", executionId)
                .query(rowMapper)
                .list();
    }

    /** Outputs of SUCCEEDED steps keyed by step name — the templating context. */
    public Map<String, Object> succeededOutputs(UUID executionId) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        jdbc.sql("""
                        select step_name, output from step_execution
                        where execution_id = :executionId and status = 'SUCCEEDED'
                        """)
                .param("executionId", executionId)
                .query((rs, rowNum) -> outputs.put(rs.getString("step_name"), json.readMap(rs.getString("output"))))
                .list();
        return outputs;
    }

    public void markRunning(UUID executionId, String stepName, int attempt, Map<String, Object> input) {
        jdbc.sql("""
                        insert into step_execution (execution_id, step_name, status, attempt, input, started_at)
                        values (:executionId, :stepName, 'RUNNING', :attempt, :input::jsonb, now())
                        on conflict (execution_id, step_name) do update
                        set status = 'RUNNING', attempt = :attempt, input = :input::jsonb,
                            started_at = coalesce(step_execution.started_at, now())
                        """)
                .param("executionId", executionId)
                .param("stepName", stepName)
                .param("attempt", attempt)
                .param("input", json.write(input))
                .update();
    }

    public void markSucceeded(UUID executionId, String stepName, Map<String, Object> output) {
        jdbc.sql("""
                        update step_execution
                        set status = 'SUCCEEDED', output = :output::jsonb, error = null, finished_at = now()
                        where execution_id = :executionId and step_name = :stepName
                        """)
                .param("executionId", executionId)
                .param("stepName", stepName)
                .param("output", json.write(output))
                .update();
    }

    public void markFailed(UUID executionId, String stepName, String error, boolean finalFailure) {
        jdbc.sql("""
                        insert into step_execution (execution_id, step_name, status, error, finished_at)
                        values (:executionId, :stepName, :status, :error, :finishedAt)
                        on conflict (execution_id, step_name) do update
                        set status = :status, error = :error, finished_at = :finishedAt
                        """)
                .param("executionId", executionId)
                .param("stepName", stepName)
                .param("status", finalFailure ? StepStatus.FAILED.name() : StepStatus.PENDING.name())
                .param("error", error)
                .param("finishedAt", finalFailure ? OffsetDateTime.now() : null)
                .update();
    }

    /** @param reason null = skipped by its own condition; non-null = upstream dependency failed */
    public void markSkipped(UUID executionId, String stepName, String reason) {
        jdbc.sql("""
                        insert into step_execution (execution_id, step_name, status, error, finished_at)
                        values (:executionId, :stepName, 'SKIPPED', :reason, now())
                        on conflict (execution_id, step_name) do update
                        set status = 'SKIPPED', error = :reason, finished_at = now()
                        """)
                .param("executionId", executionId)
                .param("stepName", stepName)
                .param("reason", reason)
                .update();
    }

    /** DLQ requeue: forget dependency-skips downstream of the revived step so they can run again. */
    public void resetDependencySkipped(UUID executionId, java.util.Collection<String> stepNames) {
        if (stepNames.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        delete from step_execution
                        where execution_id = :executionId
                          and step_name in (:names)
                          and status = 'SKIPPED'
                          and error like 'dependency failed:%'
                        """)
                .param("executionId", executionId)
                .param("names", new java.util.ArrayList<>(stepNames))
                .update();
    }

    private StepExecution mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new StepExecution(
                rs.getObject("id", UUID.class),
                rs.getObject("execution_id", UUID.class),
                rs.getString("step_name"),
                StepStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt"),
                json.readMap(rs.getString("input")),
                json.readMap(rs.getString("output")),
                rs.getString("error"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
