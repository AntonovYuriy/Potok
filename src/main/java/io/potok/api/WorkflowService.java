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
    private final io.potok.definition.WorkflowVersionRepository versions;
    private final YamlDefinitionParser parser;
    private final ApplicationEventPublisher events;

    public WorkflowService(WorkflowRepository workflows,
                           io.potok.definition.WorkflowVersionRepository versions,
                           YamlDefinitionParser parser,
                           ApplicationEventPublisher events) {
        this.workflows = workflows;
        this.versions = versions;
        this.parser = parser;
        this.events = events;
    }

    @org.springframework.transaction.annotation.Transactional
    public Workflow create(String yamlSource) {
        WorkflowDefinition definition = parser.parse(yamlSource);
        boolean subscribable = YamlDefinitionParser.parseSubscribable(yamlSource);
        try {
            Workflow workflow = workflows.insert(definition.name(), yamlSource, definition, subscribable);
            versions.insert(workflow.id(), 1, yamlSource, definition, null);
            events.publishEvent(new WorkflowsChangedEvent());
            return workflow;
        } catch (DuplicateKeyException e) {
            throw new WorkflowConflictException("workflow named '" + definition.name() + "' already exists");
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public Optional<Workflow> update(UUID id, String yamlSource) {
        return updateWithComment(id, yamlSource, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public Optional<Workflow> updateWithComment(UUID id, String yamlSource, String comment) {
        WorkflowDefinition definition = parser.parse(yamlSource);
        boolean subscribable = YamlDefinitionParser.parseSubscribable(yamlSource);
        try {
            Optional<Workflow> updated = workflows.update(
                    id, definition.name(), yamlSource, definition, subscribable);
            updated.ifPresent(w -> {
                versions.insert(w.id(), w.currentVersion(), yamlSource, definition, comment);
                events.publishEvent(new WorkflowsChangedEvent());
            });
            return updated;
        } catch (DuplicateKeyException e) {
            throw new WorkflowConflictException("workflow named '" + definition.name() + "' already exists");
        }
    }

    /** Direct flip from the dashboard — no new version, no event needed. */
    public Optional<Workflow> setSubscribable(UUID id, boolean subscribable) {
        return workflows.setSubscribable(id, subscribable);
    }

    /** Rollback = a NEW version with the old content; history is append-only. */
    @org.springframework.transaction.annotation.Transactional
    public Optional<Workflow> rollback(UUID id, int versionNo) {
        return versions.find(id, versionNo)
                .flatMap(v -> updateWithComment(id, v.yamlSource(), "rollback to v" + versionNo));
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

    public List<io.potok.definition.WorkflowVersion> versions(UUID id, int page, int size) {
        return versions.page(id, page, size);
    }

    public long versionCount(UUID id) {
        return versions.count(id);
    }

    public List<Workflow> findAll() {
        return workflows.findAll();
    }
}
