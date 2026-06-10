package io.potok.api;

import io.potok.definition.Workflow;
import io.potok.execution.ExecutionService;
import io.potok.execution.WorkflowExecution;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

    public WorkflowController(WorkflowService workflowService, ExecutionService executionService) {
        this.workflowService = workflowService;
        this.executionService = executionService;
    }

    @PostMapping(consumes = {MediaType.TEXT_PLAIN_VALUE, "application/yaml", "application/x-yaml", MediaType.ALL_VALUE})
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

    @PutMapping(value = "/{id}", consumes = {MediaType.TEXT_PLAIN_VALUE, "application/yaml", "application/x-yaml", MediaType.ALL_VALUE})
    public WorkflowResponse update(@PathVariable UUID id, @RequestBody String yamlSource) {
        return workflowService.update(id, yamlSource)
                .map(WorkflowResponse::from)
                .orElseThrow(() -> notFound(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disable(@PathVariable UUID id) {
        if (!workflowService.disable(id)) {
            throw notFound(id);
        }
        return ResponseEntity.noContent().build();
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
