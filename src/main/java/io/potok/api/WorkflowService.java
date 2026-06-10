package io.potok.api;

import io.potok.definition.Workflow;
import io.potok.definition.WorkflowDefinition;
import io.potok.definition.WorkflowRepository;
import io.potok.definition.YamlDefinitionParser;
import io.potok.trigger.WorkflowsChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkflowService {

    private final WorkflowRepository workflows;
    private final YamlDefinitionParser parser;
    private final ApplicationEventPublisher events;

    public WorkflowService(WorkflowRepository workflows,
                           YamlDefinitionParser parser,
                           ApplicationEventPublisher events) {
        this.workflows = workflows;
        this.parser = parser;
        this.events = events;
    }

    public Workflow create(String yamlSource) {
        WorkflowDefinition definition = parser.parse(yamlSource);
        try {
            Workflow workflow = workflows.insert(definition.name(), yamlSource, definition);
            events.publishEvent(new WorkflowsChangedEvent());
            return workflow;
        } catch (DuplicateKeyException e) {
            throw new WorkflowConflictException("workflow named '" + definition.name() + "' already exists");
        }
    }

    public Optional<Workflow> update(UUID id, String yamlSource) {
        WorkflowDefinition definition = parser.parse(yamlSource);
        try {
            Optional<Workflow> updated = workflows.update(id, definition.name(), yamlSource, definition);
            updated.ifPresent(w -> events.publishEvent(new WorkflowsChangedEvent()));
            return updated;
        } catch (DuplicateKeyException e) {
            throw new WorkflowConflictException("workflow named '" + definition.name() + "' already exists");
        }
    }

    /** Soft delete: enabled=false keeps execution history intact. */
    public boolean disable(UUID id) {
        boolean disabled = workflows.disable(id);
        if (disabled) {
            events.publishEvent(new WorkflowsChangedEvent());
        }
        return disabled;
    }

    public Optional<Workflow> enable(UUID id) {
        try {
            Optional<Workflow> enabled = workflows.enable(id);
            enabled.ifPresent(w -> events.publishEvent(new WorkflowsChangedEvent()));
            return enabled;
        } catch (DuplicateKeyException e) {
            throw new WorkflowConflictException(
                    "an active workflow with this name already exists — rename or disable it first");
        }
    }

    public Optional<Workflow> findById(UUID id) {
        return workflows.findById(id);
    }

    public List<Workflow> findAll() {
        return workflows.findAll();
    }
}
