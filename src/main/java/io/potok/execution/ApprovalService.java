package io.potok.execution;

import io.potok.action.TelegramClient;
import io.potok.definition.YamlDefinitionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Human-in-the-loop approvals. Asking sends ONE Telegram message with two
 * one-time links; deciding (link click, dashboard button, or timeout) marks
 * the step SUCCEEDED with {approved, timed_out} as its output and advances
 * the DAG — a timeout is a result, never a failure.
 *
 * Defaults (backward-compat rule): only 'text' is required; timeout absent
 * → 24h, channel absent → telegram, chat_id absent → TELEGRAM_CHAT_ID.
 */
@Service
public class ApprovalService {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApprovalRepository approvals;
    private final StepExecutionRepository steps;
    private final ExecutionRepository executions;
    private final JobQueueRepository jobQueue;
    private final ExecutionAdvancer advancer;
    private final TelegramClient telegram;
    private final String publicUrl;

    public ApprovalService(ApprovalRepository approvals,
                           StepExecutionRepository steps,
                           ExecutionRepository executions,
                           JobQueueRepository jobQueue,
                           ExecutionAdvancer advancer,
                           TelegramClient telegram,
                           @Value("${potok.public-url:http://localhost:8080}") String publicUrl) {
        this.approvals = approvals;
        this.steps = steps;
        this.executions = executions;
        this.jobQueue = jobQueue;
        this.advancer = advancer;
        this.telegram = telegram;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /**
     * Creates (or, on a send-retry, replaces) the approval and sends the
     * Telegram question. @return when it expires. Throws on send failure —
     * the caller applies normal retry semantics.
     */
    public Instant ask(UUID executionId, String workflowName, String stepName,
                       Map<String, Object> input) throws Exception {
        if (!telegram.isConfigured()) {
            throw new IllegalStateException(
                    "approval needs the TELEGRAM_BOT_TOKEN environment variable to send the question");
        }
        String chatId = stringOr(input.get("chat_id"), telegram.defaultChatId());
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalStateException(
                    "approval needs a chat: set TELEGRAM_CHAT_ID on the server or 'with.chat_id'");
        }
        String text = String.valueOf(input.get("text"));
        Duration timeout = Optional
                .ofNullable(YamlDefinitionParser.parseDuration(stepName, "timeout", input.get("timeout")))
                .orElse(DEFAULT_TIMEOUT);

        String approveToken = newToken();
        String denyToken = newToken();
        Instant expiresAt = Instant.now().plus(timeout);
        approvals.upsert(executionId, stepName, workflowName,
                sha256Hex(approveToken), sha256Hex(denyToken), expiresAt);

        String message = text
                + "\n\n✅ Approve: " + publicUrl + "/hooks/approval/" + approveToken
                + "\n❌ Deny: " + publicUrl + "/hooks/approval/" + denyToken
                + "\n⏳ Expires in " + humanDuration(timeout);
        HttpResponse<String> response = telegram.sendMessage(chatId, message);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("telegram sendMessage returned status "
                    + response.statusCode() + ": " + response.body());
        }
        log.info("approval_asked executionId={} step={} expiresAt={}", executionId, stepName, expiresAt);
        return expiresAt;
    }

    public enum Status { DECIDED, ALREADY_DECIDED, EXPIRED, UNKNOWN }

    public record Outcome(Status status, boolean approved, Approval approval) {
    }

    /** One link click. Single-use: the conditional update decides exactly once. */
    @Transactional
    public Outcome decideByToken(String token) {
        Optional<ApprovalRepository.TokenMatch> found = approvals.findByTokenHash(sha256Hex(token));
        if (found.isEmpty()) {
            return new Outcome(Status.UNKNOWN, false, null);
        }
        Approval approval = found.get().approval();
        boolean approved = found.get().isApprove();
        if (approval.isDecided()) {
            return new Outcome(Status.ALREADY_DECIDED, "approved".equals(approval.decision()), approval);
        }
        if (Instant.now().isAfter(approval.expiresAt())) {
            return new Outcome(Status.EXPIRED, false, approval); // the timeout job owns the result
        }
        if (!approvals.decide(approval.id(), approved ? "approved" : "denied")) {
            return new Outcome(Status.ALREADY_DECIDED, false, approval); // lost a click race
        }
        finishStep(approval, approved);
        return new Outcome(Status.DECIDED, approved, approval);
    }

    /** Dashboard buttons: same semantics, addressed by step instead of token. */
    @Transactional
    public Optional<Outcome> decideByStep(UUID executionId, String stepName, boolean approved) {
        return approvals.find(executionId, stepName).map(approval -> {
            if (approval.isDecided()) {
                return new Outcome(Status.ALREADY_DECIDED, "approved".equals(approval.decision()), approval);
            }
            if (!approvals.decide(approval.id(), approved ? "approved" : "denied")) {
                return new Outcome(Status.ALREADY_DECIDED, false, approval);
            }
            finishStep(approval, approved);
            return new Outcome(Status.DECIDED, approved, approval);
        });
    }

    private void finishStep(Approval approval, boolean approved) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approved", approved);
        output.put("timed_out", false);
        output.put("decided_at", Instant.now().toString());
        steps.markSucceeded(approval.executionId(), approval.stepName(), output);
        // cancel the parked timeout wake-up; a late fire would be a harmless no-op anyway
        jobQueue.deleteByExecutionAndStep(approval.executionId(), approval.stepName());
        executions.findById(approval.executionId())
                .ifPresent(execution -> advancer.resume(execution, approval.workflowName()));
        log.info("approval_decided executionId={} step={} approved={}",
                approval.executionId(), approval.stepName(), approved);
    }

    private static String stringOr(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String humanDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds % 86_400 == 0) {
            return (seconds / 86_400) + "d";
        }
        if (seconds % 3_600 == 0) {
            return (seconds / 3_600) + "h";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }
}
