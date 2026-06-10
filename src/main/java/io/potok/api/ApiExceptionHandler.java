package io.potok.api;

import io.potok.definition.InvalidDefinitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to RFC 7807 problem+json. ResponseStatusException and
 * framework errors are already handled by Spring's problemdetails support.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidDefinitionException.class)
    public ProblemDetail invalidDefinition(InvalidDefinitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid workflow definition");
        return problem;
    }

    @ExceptionHandler(WorkflowConflictException.class)
    public ProblemDetail conflict(WorkflowConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Workflow conflict");
        return problem;
    }
}
