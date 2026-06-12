package io.potok.api;

import io.potok.execution.ExecutionRepository;
import io.potok.execution.StepExecution;
import io.potok.execution.StepExecutionRepository;
import io.potok.execution.WorkflowExecution;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {


    private final ExecutionRepository executions;
    private final StepExecutionRepository steps;

    private final io.potok.execution.ApprovalService approvalService;

    public ExecutionController(ExecutionRepository executions, StepExecutionRepository steps,
                               io.potok.execution.ApprovalService approvalService) {
        this.approvalService = approvalService;
        this.executions = executions;
        this.steps = steps;
    }

    @GetMapping
    public List<ExecutionResponse> list(
            @RequestParam(required = false) UUID workflowId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        int boundedSize = Math.min(Math.max(size, 1), 200);
        return executions.list(workflowId, Math.max(page, 0), boundedSize).stream()
                .map(execution -> ExecutionResponse.from(execution, null))
                .toList();
    }

    /** Dashboard Approve/Deny buttons — same one-time semantics as the Telegram links. */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/steps/{stepName}/decide")
    public Map<String, Object> decide(@PathVariable UUID id,
                                      @PathVariable String stepName,
                                      @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        return approvalService.decideByStep(id, stepName, approved)
                .map(outcome -> Map.<String, Object>of(
                        "status", outcome.status().name(),
                        "approved", outcome.approved()))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "no approval waiting on step '" + stepName + "'"));
    }

    @GetMapping("/{id}")
    public ExecutionResponse get(@PathVariable UUID id) {
        WorkflowExecution execution = executions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "execution " + id + " not found"));
        List<StepExecution> stepExecutions = steps.findByExecution(id);
        return ExecutionResponse.from(execution, stepExecutions);
    }
}
