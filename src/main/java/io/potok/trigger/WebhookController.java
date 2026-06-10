package io.potok.trigger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.potok.definition.Workflow;
import io.potok.definition.WorkflowRepository;
import io.potok.execution.ExecutionService;
import io.potok.execution.WorkflowExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Webhook trigger: POST /hooks/{path} starts an execution; the JSON body becomes
 * the trigger payload. When the workflow sets trigger.webhook.hmac_secret_env,
 * the raw body must carry a valid X-Hub-Signature-256 (verified BEFORE parsing).
 */
@RestController
@RequestMapping("/hooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WorkflowRepository workflows;
    private final ExecutionService executionService;
    private final WebhookSignatureVerifier signatures;
    private final ObjectMapper objectMapper;

    public WebhookController(WorkflowRepository workflows, ExecutionService executionService,
                             WebhookSignatureVerifier signatures, ObjectMapper objectMapper) {
        this.workflows = workflows;
        this.executionService = executionService;
        this.signatures = signatures;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{path}")
    public ResponseEntity<Map<String, Object>> trigger(
            @PathVariable String path,
            @RequestHeader(value = WebhookSignatureVerifier.HEADER, required = false) String signature,
            @RequestBody(required = false) byte[] rawBody) {
        Workflow workflow = workflows.findEnabledByWebhookPath(path)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no enabled workflow with webhook path '" + path + "'"));

        String secretEnv = workflow.definition().trigger().webhook().hmacSecretEnv();
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        if (secretEnv != null) {
            WebhookSignatureVerifier.Result result = signatures.verify(secretEnv, signature, body);
            if (result != WebhookSignatureVerifier.Result.OK) {
                log.warn("webhook_rejected path={} reason={}", path, result);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        result == WebhookSignatureVerifier.Result.SECRET_NOT_CONFIGURED
                                ? "webhook signature secret is not configured on the server"
                                : "missing or invalid " + WebhookSignatureVerifier.HEADER + " signature");
            }
        }

        Map<String, Object> payload = parsePayload(body);
        WorkflowExecution execution = executionService.start(workflow, Map.of(
                "type", "webhook",
                "path", path,
                "payload", payload));

        return ResponseEntity.accepted().body(Map.of(
                "executionId", execution.id(),
                "workflowId", workflow.id(),
                "status", execution.status().name()));
    }

    private Map<String, Object> parsePayload(byte[] body) {
        if (body.length == 0) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (java.io.IOException e) {
            // non-JSON bodies are still valid triggers; keep the raw text
            return Map.of("raw", new String(body, StandardCharsets.UTF_8));
        }
    }
}
