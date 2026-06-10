package io.potok.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkflowVersionRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<WorkflowVersion> rowMapper;

    public WorkflowVersionRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rowMapper = this::mapRow;
    }

    public void insert(UUID workflowId, int versionNo, String yamlSource,
                       WorkflowDefinition definition, String comment) {
        jdbc.sql("""
                        insert into workflow_version (workflow_id, version_no, yaml_source, definition, comment)
                        values (:workflowId, :versionNo, :yaml, :definition::jsonb, :comment)
                        """)
                .param("workflowId", workflowId)
                .param("versionNo", versionNo)
                .param("yaml", yamlSource)
                .param("definition", toJson(definition))
                .param("comment", comment)
                .update();
    }

    public List<WorkflowVersion> page(UUID workflowId, int page, int size) {
        return jdbc.sql("""
                        select * from workflow_version
                        where workflow_id = :workflowId
                        order by version_no desc
                        limit :limit offset :offset
                        """)
                .param("workflowId", workflowId)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query(rowMapper)
                .list();
    }

    public long count(UUID workflowId) {
        return jdbc.sql("select count(*) from workflow_version where workflow_id = :workflowId")
                .param("workflowId", workflowId)
                .query(Long.class)
                .single();
    }

    public Optional<WorkflowVersion> find(UUID workflowId, int versionNo) {
        return jdbc.sql("""
                        select * from workflow_version
                        where workflow_id = :workflowId and version_no = :versionNo
                        """)
                .param("workflowId", workflowId)
                .param("versionNo", versionNo)
                .query(rowMapper)
                .optional();
    }

    private WorkflowVersion mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new WorkflowVersion(
                    rs.getObject("workflow_id", UUID.class),
                    rs.getInt("version_no"),
                    rs.getString("yaml_source"),
                    objectMapper.readValue(rs.getString("definition"), WorkflowDefinition.class),
                    rs.getString("comment"),
                    rs.getObject("created_at", OffsetDateTime.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize workflow version definition", e);
        }
    }

    private String toJson(WorkflowDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize workflow definition", e);
        }
    }
}
