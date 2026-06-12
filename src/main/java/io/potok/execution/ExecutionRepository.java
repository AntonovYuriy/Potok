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
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final RowMapper<WorkflowExecution> rowMapper;

    public ExecutionRepository(JdbcClient jdbc, Json json,
                               com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.json = json;
        this.objectMapper = objectMapper;
        this.rowMapper = this::mapRow;
    }

    public WorkflowExecution insert(UUID workflowId, Map<String, Object> triggerInfo,
                                    int versionNo, io.potok.definition.WorkflowDefinition definition) {
        try {
            return jdbc.sql("""
                            insert into workflow_execution (workflow_id, trigger_info, version_no, definition)
                            values (:workflowId, :triggerInfo::jsonb, :versionNo, :definition::jsonb)
                            returning *
                            """)
                    .param("workflowId", workflowId)
                    .param("triggerInfo", json.write(triggerInfo))
                    .param("versionNo", versionNo)
                    .param("definition", objectMapper.writeValueAsString(definition))
                    .query(rowMapper)
                    .single();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to snapshot definition", e);
        }
    }

    public Optional<WorkflowExecution> findById(UUID id) {
        return jdbc.sql("select * from workflow_execution where id = :id")
                .param("id", id)
                .query(rowMapper)
                .optional();
    }

    public List<WorkflowExecution> list(UUID workflowId, int page, int size) {
        StringBuilder sql = new StringBuilder("select * from workflow_execution");
        if (workflowId != null) {
            sql.append(" where workflow_id = :workflowId");
        }
        sql.append(" order by created_at desc limit :limit offset :offset");
        var spec = jdbc.sql(sql.toString())
                .param("limit", size)
                .param("offset", (long) page * size);
        if (workflowId != null) {
            spec = spec.param("workflowId", workflowId);
        }
        return spec.query(rowMapper).list();
    }

    /** PENDING/WAITING -> RUNNING transition (WAITING resumes on wake/decision); no-op otherwise. */
    public void markRunning(UUID id) {
        jdbc.sql("""
                        update workflow_execution
                        set status = 'RUNNING', started_at = coalesce(started_at, now())
                        where id = :id and status in ('PENDING', 'WAITING')
                        """)
                .param("id", id)
                .update();
    }

    /** RUNNING/PENDING -> WAITING: the execution has a step durably parked (wait/approval). */
    public void markWaiting(UUID id) {
        jdbc.sql("""
                        update workflow_execution
                        set status = 'WAITING', started_at = coalesce(started_at, now())
                        where id = :id and status in ('PENDING', 'RUNNING')
                        """)
                .param("id", id)
                .update();
    }

    /** @return true when this call performed the transition (guards double-finish from racing branches) */
    public boolean markFinished(UUID id, ExecutionStatus status) {
        return jdbc.sql("""
                        update workflow_execution
                        set status = :status, finished_at = now()
                        where id = :id and status in ('PENDING', 'RUNNING', 'WAITING')
                        """)
                .param("id", id)
                .param("status", status.name())
                .update() > 0;
    }

    private WorkflowExecution mapRow(ResultSet rs, int rowNum) throws SQLException {
        String definitionJson = rs.getString("definition");
        io.potok.definition.WorkflowDefinition definition = null;
        if (definitionJson != null) {
            try {
                definition = objectMapper.readValue(definitionJson, io.potok.definition.WorkflowDefinition.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("failed to read definition snapshot", e);
            }
        }
        return new WorkflowExecution(
                rs.getObject("id", UUID.class),
                rs.getObject("workflow_id", UUID.class),
                ExecutionStatus.valueOf(rs.getString("status")),
                json.readMap(rs.getString("trigger_info")),
                rs.getObject("version_no", Integer.class),
                definition,
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
