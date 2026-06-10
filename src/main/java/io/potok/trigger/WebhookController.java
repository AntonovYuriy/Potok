package io.potok.trigger;

import io.potok.definition.Workflow;
import io.potok.definition.WorkflowRepository;
import io.potok.execution.ExecutionService;
import io.potok.execution.WorkflowExecution;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Webhook trigger: POST /hooks/{path} starts an execution; the JSON body becomes the trigger payload. */
@RestController
@RequestMapping("/hooks")
public class WebhookController {

    private final WorkflowRepository workflows;
    private final ExecutionService executionService;

    public WebhookController(WorkflowRepository workflows, ExecutionService executionService) {
        this.workflows = workflows;
        this.executionService = executionService;
    }

    @PostMapping("/{path}")
    public ResponseEntity<Map<String, Object>> trigger(
            @PathVariable String path,
            @RequestBody(required = false) Map<String, Object> payload) {
        Workflow workflow = workflows.findEnabledByWebhookPath(path)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no enabled workflow with webhook path '" + path + "'"));

        WorkflowExecution execution = executionService.start(workflow, Map.of(
                "type", "webhook",
                "path", path,
                "payload", payload == null ? Map.of() : payload));

        return ResponseEntity.accepted().body(Map.of(
                "executionId", execution.id(),
                "workflowId", workflow.id(),
                "status", execution.status().name()));
    }
}
