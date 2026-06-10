package io.potok.execution;

import io.potok.common.Json;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ExecutionRepository {

    private final JdbcClient jdbc;
    private final Json json;
    private final RowMapper<WorkflowExecution> rowMapper;

    public ExecutionRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = this::mapRow;
    }

    public WorkflowExecution insert(UUID workflowId, Map<String, Object> triggerInfo) {
        return jdbc.sql("""
                        insert into workflow_execution (workflow_id, trigger_info)
                        values (:workflowId, :triggerInfo::jsonb)
                        returning *
                        """)
                .param("workflowId", workflowId)
                .param("triggerInfo", json.write(triggerInfo))
                .query(rowMapper)
                .single();
    }

    public Optional<WorkflowExecution> findById(UUID id) {
        return jdbc.sql("select * from workflow_execution where id = :id")
                .param("id", id)
                .query(rowMapper)
                .optional();
    }

    public List<WorkflowExecution> list(UUID workflowId) {
        StringBuilder sql = new StringBuilder("select * from workflow_execution");
        if (workflowId != null) {
            sql.append(" where workflow_id = :workflowId");
        }
        sql.append(" order by created_at desc limit 100");
        var spec = jdbc.sql(sql.toString());
        if (workflowId != null) {
            spec = spec.param("workflowId", workflowId);
        }
        return spec.query(rowMapper).list();
    }

    /** PENDING -> RUNNING transition; no-op when already running or finished. */
    public void markRunning(UUID id) {
        jdbc.sql("""
                        update workflow_execution
                        set status = 'RUNNING', started_at = coalesce(started_at, now())
                        where id = :id and status = 'PENDING'
                        """)
                .param("id", id)
                .update();
    }

    /** @return true when this call performed the transition (guards double-finish from racing branches) */
    public boolean markFinished(UUID id, ExecutionStatus status) {
        return jdbc.sql("""
                        update workflow_execution
                        set status = :status, finished_at = now()
                        where id = :id and status in ('PENDING', 'RUNNING')
                        """)
                .param("id", id)
                .param("status", status.name())
                .update() > 0;
    }

    private WorkflowExecution mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowExecution(
                rs.getObject("id", UUID.class),
                rs.getObject("workflow_id", UUID.class),
                ExecutionStatus.valueOf(rs.getString("status")),
                json.readMap(rs.getString("trigger_info")),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
