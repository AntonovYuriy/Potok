package io.potok.api;

import io.potok.execution.DeadLetter;
import io.potok.execution.DeadLetterRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dlq")
public class DlqController {

    private static final int MAX_PAGE_SIZE = 200;

    private final DeadLetterRepository deadLetters;
    private final io.potok.execution.ExecutionRepository executions;
    private final io.potok.definition.WorkflowRepository workflows;

    public DlqController(DeadLetterRepository deadLetters,
                         io.potok.execution.ExecutionRepository executions,
                         io.potok.definition.WorkflowRepository workflows) {
        this.deadLetters = deadLetters;
        this.executions = executions;
        this.workflows = workflows;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int boundedPage = Math.max(page, 0);
        List<DeadLetter> items = deadLetters.page(boundedPage, boundedSize);
        return Map.of(
                "items", items,
                "page", boundedPage,
                "size", boundedSize,
                "total", deadLetters.count());
    }

    @PostMapping("/{id}/requeue")
    public ResponseEntity<Map<String, Object>> requeue(@PathVariable long id) {
        DeadLetter deadLetter = deadLetters.findById(id).orElseThrow(() -> notFound(id));
        // un-skip what was poisoned by this step's failure so the DAG can finish
        java.util.Set<String> downstream = executions.findById(deadLetter.executionId())
                .flatMap(execution -> workflows.findById(execution.workflowId()))
                .map(workflow -> workflow.definition().downstreamClosure(deadLetter.stepName()))
                .orElse(java.util.Set.of());
        deadLetters.requeue(deadLetter, downstream);
        return ResponseEntity.accepted().body(Map.of(
                "executionId", deadLetter.executionId(),
                "stepName", deadLetter.stepName(),
                "requeued", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        if (!deadLetters.delete(id)) {
            throw notFound(id);
        }
        return ResponseEntity.noContent().build();
    }

    private static ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "dead letter " + id + " not found");
    }
}
