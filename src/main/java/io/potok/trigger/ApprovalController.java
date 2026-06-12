package io.potok.trigger;

import io.potok.execution.ApprovalService;
import io.potok.execution.ApprovalService.Outcome;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The approve/deny links from the Telegram message land here. Public like
 * webhooks — the one-time token IS the credential. Responses are tiny HTML
 * pages; nothing beyond the workflow name is disclosed.
 */
@RestController
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping(value = "/hooks/approval/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> decide(@PathVariable String token) {
        Outcome outcome = approvalService.decideByToken(token);
        return switch (outcome.status()) {
            case DECIDED -> ResponseEntity.ok(page(outcome.approved()
                    ? "✅ Approved"
                    : "❌ Denied",
                    "Workflow \"" + outcome.approval().workflowName() + "\" continues with your decision."));
            case ALREADY_DECIDED -> ResponseEntity.ok(page("Already decided",
                    "This question was already answered (" + outcome.approval().decision()
                            + "). Nothing changed."));
            case EXPIRED -> ResponseEntity.status(410).body(page("⌛ Expired",
                    "This approval timed out before the link was used."));
            case UNKNOWN -> ResponseEntity.status(404).body(page("Link invalid",
                    "This approval link is unknown or no longer valid."));
        };
    }

    private static String page(String title, String detail) {
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>%s — Potok</title>
                <style>body{font-family:system-ui;background:#0e1116;color:#dce3ec;display:flex;
                align-items:center;justify-content:center;height:100vh;margin:0}
                div{text-align:center;max-width:28rem;padding:2rem}
                h1{font-size:1.6rem}p{color:#8b96a5}</style></head>
                <body><div><h1>%s</h1><p>%s</p></div></body></html>
                """.formatted(title, title, escape(detail));
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
