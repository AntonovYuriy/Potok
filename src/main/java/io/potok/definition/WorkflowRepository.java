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
public class WorkflowRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<Workflow> rowMapper;

    public WorkflowRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rowMapper = this::mapRow;
    }

    public Workflow insert(String name, String yamlSource, WorkflowDefinition definition) {
        return jdbc.sql("""
                        insert into workflow (name, yaml_source, definition)
                        values (:name, :yaml, :definition::jsonb)
                        returning *
                        """)
                .param("name", name)
                .param("yaml", yamlSource)
                .param("definition", toJson(definition))
                .query(rowMapper)
                .single();
    }

    public Optional<Workflow> update(UUID id, String name, String yamlSource, WorkflowDefinition definition) {
        return jdbc.sql("""
                        update workflow
                        set name = :name, yaml_source = :yaml, definition = :definition::jsonb,
                            enabled = true, updated_at = now()
                        where id = :id
                        returning *
                        """)
                .param("id", id)
                .param("name", name)
                .param("yaml", yamlSource)
                .param("definition", toJson(definition))
                .query(rowMapper)
                .optional();
    }

    public boolean disable(UUID id) {
        return jdbc.sql("update workflow set enabled = false, updated_at = now() where id = :id")
                .param("id", id)
                .update() > 0;
    }

    public Optional<Workflow> findById(UUID id) {
        return jdbc.sql("select * from workflow where id = :id")
                .param("id", id)
                .query(rowMapper)
                .optional();
    }

    public List<Workflow> findAll() {
        return jdbc.sql("select * from workflow order by created_at")
                .query(rowMapper)
                .list();
    }

    public List<Workflow> findEnabledWithCron() {
        return jdbc.sql("select * from workflow where enabled and definition -> 'trigger' ->> 'cron' is not null")
                .query(rowMapper)
                .list();
    }

    public Optional<Workflow> findEnabledByWebhookPath(String path) {
        return jdbc.sql("""
                        select * from workflow
                        where enabled and definition -> 'trigger' -> 'webhook' ->> 'path' = :path
                        """)
                .param("path", path)
                .query(rowMapper)
                .optional();
    }

    public boolean existsByName(String name) {
        return jdbc.sql("select count(*) from workflow where name = :name")
                .param("name", name)
                .query(Long.class)
                .single() > 0;
    }

    private Workflow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Workflow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                rs.getString("yaml_source"),
                fromJson(rs.getString("definition")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String toJson(WorkflowDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize workflow definition", e);
        }
    }

    private WorkflowDefinition fromJson(String json) {
        try {
            return objectMapper.readValue(json, WorkflowDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize workflow definition", e);
        }
    }
}
