package io.potok.api;

import io.potok.definition.Workflow;
import io.potok.execution.ExecutionService;
import io.potok.execution.WorkflowExecution;
import io.potok.recipient.Recipient;
import io.potok.subscription.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Control plane for workflow definitions. Request bodies are raw YAML, responses are JSON. */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final ExecutionService executionService;
    private final SubscriptionService subscriptionService;

    public WorkflowController(WorkflowService workflowService,
                              ExecutionService executionService,
                              SubscriptionService subscriptionService) {
        this.workflowService = workflowService;
        this.executionService = executionService;
        this.subscriptionService = subscriptionService;
    }

    // No ALL_VALUE here: curl's default form-urlencoded would arrive mangled
    // (the container consumes the stream as parameters); a 415 is clearer.
    @PostMapping(consumes = {MediaType.TEXT_PLAIN_VALUE, "application/yaml", "application/x-yaml", "text/yaml"})
    public ResponseEntity<WorkflowResponse> create(@RequestBody String yamlSource) {
        Workflow workflow = workflowService.create(yamlSource);
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkflowResponse.from(workflow));
    }

    @GetMapping
    public List<WorkflowResponse> list() {
        return workflowService.findAll().stream().map(WorkflowResponse::from).toList();
    }

    @GetMapping("/{id}")
    public WorkflowResponse get(@PathVariable UUID id) {
        return workflowService.findById(id)
                .map(WorkflowResponse::from)
                .orElseThrow(() -> notFound(id));
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.TEXT_PLAIN_VALUE, "application/yaml", "application/x-yaml", "text/yaml"})
    public WorkflowResponse update(@PathVariable UUID id, @RequestBody String yamlSource) {
        return workflowService.update(id, yamlSource)
                .map(WorkflowResponse::from)
                .orElseThrow(() -> notFound(id));
    }

    /**
     * Default (soft) delete disables the workflow, keeping its history.
     * {@code ?permanent=true} hard-deletes it and all its history — allowed
     * only when the workflow is already disabled (409 otherwise).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean permanent) {
        if (!permanent) {
            if (!workflowService.disable(id)) {
                throw notFound(id);
            }
            return ResponseEntity.noContent().build();
        }
        switch (workflowService.deletePermanently(id)) {
            case NOT_FOUND -> throw notFound(id);
            case ENABLED -> throw new WorkflowConflictException(
                    "workflow must be disabled before it can be permanently deleted");
            case DELETED -> { /* fall through to 204 */ }
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    public WorkflowResponse enable(@PathVariable UUID id) {
        return workflowService.enable(id).map(WorkflowResponse::from).orElseThrow(() -> notFound(id));
    }

    @GetMapping("/{id}/versions")
    public Map<String, Object> versions(@PathVariable UUID id,
                                        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
                                        @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        workflowService.findById(id).orElseThrow(() -> notFound(id));
        int boundedSize = Math.min(Math.max(size, 1), 100);
        return Map.of(
                "items", workflowService.versions(id, Math.max(page, 0), boundedSize),
                "total", workflowService.versionCount(id),
                "page", Math.max(page, 0),
                "size", boundedSize);
    }

    /** Rollback = append a new version with the old content; history is never rewritten. */
    @PostMapping("/{id}/versions/{versionNo}/rollback")
    public WorkflowResponse rollback(@PathVariable UUID id, @PathVariable int versionNo) {
        return workflowService.rollback(id, versionNo)
                .map(WorkflowResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "workflow " + id + " or version " + versionNo + " not found"));
    }

    /**
     * Direct flag flip — does NOT create a new version. The dashboard toggle
     * uses this; the canonical value still lives in YAML for create/update.
     */
    @PatchMapping("/{id}/subscribable")
    public WorkflowResponse setSubscribable(@PathVariable UUID id,
                                            @RequestBody Map<String, Boolean> body) {
        Boolean value = body == null ? null : body.get("subscribable");
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "body must be { \"subscribable\": true|false }");
        }
        return workflowService.setSubscribable(id, value)
                .map(WorkflowResponse::from)
                .orElseThrow(() -> notFound(id));
    }

    /** APPROVED recipients subscribed to this workflow (revoked rows excluded). */
    @GetMapping("/{id}/subscribers")
    public Map<String, Object> subscribers(@PathVariable UUID id) {
        workflowService.findById(id).orElseThrow(() -> notFound(id));
        List<Recipient> rows = subscriptionService.listApprovedSubscribers(id);
        List<Map<String, Object>> items = rows.stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.id(),
                        "displayName", r.displayName(),
                        "chatIdMasked", RecipientController.maskChatId(r.chatId()),
                        "approvedAt", r.approvedAt()))
                .toList();
        return Map.of("items", items, "total", items.size());
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable UUID id) {
        Workflow workflow = workflowService.findById(id).orElseThrow(() -> notFound(id));
        WorkflowExecution execution = executionService.start(workflow,
                Map.of("type", "manual", "payload", Map.of()));
        return ResponseEntity.accepted().body(Map.of(
                "executionId", execution.id(),
                "status", execution.status().name()));
    }

    private static ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow " + id + " not found");
    }
}
