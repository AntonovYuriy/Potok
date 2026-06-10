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
import java.util.UUID;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private final ExecutionRepository executions;
    private final StepExecutionRepository steps;

    public ExecutionController(ExecutionRepository executions, StepExecutionRepository steps) {
        this.executions = executions;
        this.steps = steps;
    }

    @GetMapping
    public List<ExecutionResponse> list(@RequestParam(required = false) UUID workflowId) {
        return executions.list(workflowId).stream()
                .map(execution -> ExecutionResponse.from(execution, null))
                .toList();
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
