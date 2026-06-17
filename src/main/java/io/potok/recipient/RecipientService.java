package io.potok.recipient;

import io.potok.api.SettingsRepository;
import io.potok.recipient.Recipient.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Recipient state machine. Centralises legal transitions so the bot ingest,
 * REST API, and tests all share one truth.
 *
 * <pre>
 *  (new chat) --auto-approve OFF--> PENDING --approve--> APPROVED
 *              --auto-approve ON ---> APPROVED <--re-approve-- REVOKED
 *                                       ^                       ^
 *                                       |                       |
 *                                       +--- revoke / /stop ----+
 * </pre>
 *
 * Invalid transitions throw {@link IllegalStateException} so tests can pin
 * them. Authorisation never touches this — control plane access is the API
 * key / api_token, always.
 */
@Service
public class RecipientService {

    private static final Logger log = LoggerFactory.getLogger(RecipientService.class);
    private static final String AUTO_APPROVE_KEY = "telegram_auto_approve";

    private final RecipientRepository recipients;
    private final SettingsRepository settings;

    public RecipientService(RecipientRepository recipients, SettingsRepository settings) {
        this.recipients = recipients;
        this.settings = settings;
    }

    /**
     * Called when ANY Telegram message arrives from a chat. Upserts the row,
     * refreshes display name and last-seen. New chats land PENDING unless
     * telegram_auto_approve = true.
     * @return the post-state row + whether this call created it
     */
    public Contact onIncomingMessage(String chatId, String displayName) {
        Optional<Recipient> existing = recipients.findByChatId(chatId);
        if (existing.isPresent()) {
            Recipient updated = recipients.upsertOnContact(chatId, displayName, existing.get().status());
            return new Contact(updated, false);
        }
        boolean autoApprove = settings.getBoolean(AUTO_APPROVE_KEY, false);
        Status status = autoApprove ? Status.APPROVED : Status.PENDING;
        Recipient created = recipients.upsertOnContact(chatId, displayName, status);
        log.info("recipient_registered chatId={} status={} autoApprove={}", chatId, status, autoApprove);
        return new Contact(created, true);
    }

    /** /stop from the chat — self-revoke. Idempotent; PENDING also goes to REVOKED. */
    public void selfRevoke(String chatId) {
        recipients.findByChatId(chatId).ifPresent(recipient ->
                recipients.updateStatus(recipient.id(), Status.REVOKED));
    }

    /**
     * Pure bot reply logic — sequenced for testability: upsert first, then
     * decide what to say back. Returns the optional reply line; never throws
     * (Telegram I/O is up to the caller).
     */
    public Optional<String> handleBotMessage(String chatId, String displayName, String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.equalsIgnoreCase("/stop")) {
            selfRevoke(chatId);
            return Optional.of("🔕 You have been unsubscribed. Send /start to ask again.");
        }
        Contact contact = onIncomingMessage(chatId, displayName);
        Recipient recipient = contact.recipient();
        boolean startCommand = text.equalsIgnoreCase("/start");
        if (text.equalsIgnoreCase("/status")) {
            return Optional.of(statusMessage(recipient));
        }
        return switch (recipient.status()) {
            case APPROVED -> {
                if (contact.justRegistered()) {
                    yield Optional.of("✅ You're subscribed — you'll receive notifications "
                            + "from the Potok workflows that send to approved recipients.\n"
                            + "Send /stop to unsubscribe.");
                }
                if (startCommand) {
                    yield Optional.of("✅ Already subscribed. Send /stop to unsubscribe.");
                }
                yield Optional.empty();
            }
            case PENDING -> {
                if (contact.justRegistered() || startCommand) {
                    yield Optional.of("👋 Request received — waiting for the owner to approve.\n"
                            + "This bot only routes notifications; it cannot give you access to Potok.");
                }
                yield Optional.empty();
            }
            case REVOKED -> startCommand
                    ? Optional.of("🚫 Access was revoked. Ask the owner to re-approve.")
                    : Optional.empty();
        };
    }

    private String statusMessage(Recipient recipient) {
        return switch (recipient.status()) {
            case APPROVED -> "✅ Subscribed. Send /stop to unsubscribe.";
            case PENDING -> "👋 Pending approval by the owner.";
            case REVOKED -> "🚫 Revoked. Ask the owner to re-approve.";
        };
    }

    public Recipient approve(UUID id) {
        return transition(id, Status.APPROVED, Status.PENDING, Status.REVOKED);
    }

    public Recipient revoke(UUID id) {
        return transition(id, Status.REVOKED, Status.PENDING, Status.APPROVED);
    }

    public List<Recipient> listApproved() {
        return recipients.listApproved();
    }

    public Optional<Recipient> findApprovedByIdOrName(String idOrName) {
        return recipients.findApprovedByIdOrName(idOrName);
    }

    private Recipient transition(UUID id, Status target, Status... allowedFrom) {
        Recipient recipient = recipients.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown recipient: " + id));
        if (recipient.status() == target) {
            return recipient;
        }
        for (Status allowed : allowedFrom) {
            if (recipient.status() == allowed) {
                recipients.updateStatus(id, target);
                log.info("recipient_transition id={} from={} to={}", id, recipient.status(), target);
                return recipients.findById(id).orElseThrow();
            }
        }
        throw new IllegalStateException("illegal transition " + recipient.status() + " -> " + target);
    }

    public record Contact(Recipient recipient, boolean justRegistered) {
    }
}
