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
    private final io.potok.action.ActionRegistry actions;

    public WorkflowService(WorkflowRepository workflows,
                           io.potok.definition.WorkflowVersionRepository versions,
                           YamlDefinitionParser parser,
                           ApplicationEventPublisher events,
                           io.potok.action.ActionRegistry actions) {
        this.workflows = workflows;
        this.versions = versions;
        this.parser = parser;
        this.events = events;
        this.actions = actions;
    }

    @org.springframework.transaction.annotation.Transactional
    public Workflow create(String yamlSource) {
        WorkflowDefinition definition = parser.parse(yamlSource);
        validateActionTypes(definition);
        requireFreeWebhookPath(definition, null);
        boolean subscribable = YamlDefinitionParser.parseSubscribable(yamlSource);
        try {
            Workflow workflow = workflows.insert(definition.name(), yamlSource, definition, subscribable);
            versions.insert(workflow.id(), 1, yamlSource, definition, null);
            events.publishEvent(new WorkflowsChangedEvent());
            return workflow;
        } catch (DuplicateKeyException e) {
            throw conflictFrom(definition);
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public Optional<Workflow> update(UUID id, String yamlSource) {
        return updateWithComment(id, yamlSource, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public Optional<Workflow> updateWithComment(UUID id, String yamlSource, String comment) {
        WorkflowDefinition definition = parser.parse(yamlSource);
        validateActionTypes(definition);
        requireFreeWebhookPath(definition, id);
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
            throw conflictFrom(definition);
        }
    }

    /**
     * A typo'd action ("telegran") used to create fine and fail only at run time.
     * Reject it at create/update instead. 'approval' and 'wait' are engine
     * concepts, not registered handlers, so they pass separately.
     */
    private void validateActionTypes(WorkflowDefinition definition) {
        java.util.List<String> available = new java.util.ArrayList<>(actions.types());
        available.add("approval");
        java.util.Collections.sort(available);
        for (WorkflowDefinition.Step step : definition.steps()) {
            String action = step.action();
            if (action != null && !"approval".equals(action) && actions.find(action) == null) {
                throw new io.potok.definition.InvalidDefinitionException(
                        "step '" + step.name() + "': unknown action '" + action
                                + "'; available: " + available);
            }
        }
    }

    /**
     * A webhook path shared by two ENABLED workflows would make every delivery
     * ambiguous (it used to 500) — reject it up front; the V16 partial unique
     * index backstops concurrent creates.
     */
    private void requireFreeWebhookPath(WorkflowDefinition definition, UUID selfId) {
        WorkflowDefinition.Webhook webhook = definition.trigger().webhook();
        if (webhook != null && workflows.enabledWebhookPathTaken(webhook.path(), selfId)) {
            throw new WorkflowConflictException("an active workflow already listens on webhook path '"
                    + webhook.path() + "' — pick another path or disable that workflow first");
        }
    }

    /** Race-window backstop when the DB unique indexes fire before our checks. */
    private WorkflowConflictException conflictFrom(WorkflowDefinition definition) {
        WorkflowDefinition.Webhook webhook = definition.trigger().webhook();
        String hint = webhook == null
                ? "workflow named '" + definition.name() + "' already exists"
                : "workflow name '" + definition.name() + "' or webhook path '"
                        + webhook.path() + "' is already in use by an active workflow";
        return new WorkflowConflictException(hint);
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

    public enum DeleteResult { NOT_FOUND, ENABLED, DELETED }

    /**
     * Hard delete — wipes the workflow and all its history. Refuses unless the
     * workflow is already disabled, so a live workflow can never vanish from
     * under a running trigger.
     */
    @org.springframework.transaction.annotation.Transactional
    public DeleteResult deletePermanently(UUID id) {
        Optional<Workflow> workflow = workflows.findById(id);
        if (workflow.isEmpty()) {
            return DeleteResult.NOT_FOUND;
        }
        if (workflow.get().enabled()) {
            return DeleteResult.ENABLED;
        }
        workflows.hardDelete(id);
        events.publishEvent(new WorkflowsChangedEvent());
        return DeleteResult.DELETED;
    }

    public Optional<Workflow> enable(UUID id) {
        // Re-enabling must respect webhook-path uniqueness like create/update does.
        workflows.findById(id).ifPresent(w -> requireFreeWebhookPath(w.definition(), id));
        try {
            Optional<Workflow> enabled = workflows.enable(id);
            enabled.ifPresent(w -> events.publishEvent(new WorkflowsChangedEvent()));
            return enabled;
        } catch (DuplicateKeyException e) {
            throw new WorkflowConflictException(
                    "an active workflow with this name or webhook path already exists "
                            + "— rename, change the path, or disable it first");
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
