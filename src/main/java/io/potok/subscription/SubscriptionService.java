package io.potok.subscription;

import io.potok.definition.Workflow;
import io.potok.definition.WorkflowRepository;
import io.potok.recipient.Recipient;
import io.potok.recipient.RecipientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription rules:
 * <ul>
 *   <li>Only APPROVED recipients may subscribe. PENDING/REVOKED toggles are
 *       rejected at the service boundary even if a stale button reaches the
 *       poller — the menu's button list is also gated, but defence in depth.</li>
 *   <li>Only {@code subscribable AND enabled} workflows accept new subscriptions.
 *       Existing rows for a workflow that gets disabled or un-subscribable are
 *       harmless — fan-out queries join on the workflow state too.</li>
 *   <li>{@link #toggle} returns the post-state so the menu redraw is one round-trip.</li>
 * </ul>
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptions;
    private final WorkflowRepository workflows;
    private final RecipientRepository recipients;

    public SubscriptionService(SubscriptionRepository subscriptions,
                               WorkflowRepository workflows,
                               RecipientRepository recipients) {
        this.subscriptions = subscriptions;
        this.workflows = workflows;
        this.recipients = recipients;
    }

    /**
     * @return the new subscription state (true = subscribed). Returns the
     * existing state when the action is rejected, so the menu redraw still
     * shows reality.
     */
    public ToggleOutcome toggle(UUID workflowId, UUID recipientId) {
        Recipient recipient = recipients.findById(recipientId).orElse(null);
        if (recipient == null || recipient.status() != Recipient.Status.APPROVED) {
            return new ToggleOutcome(false, false, "not authorised");
        }
        Workflow workflow = workflows.findById(workflowId).orElse(null);
        if (workflow == null || !workflow.subscribable() || !workflow.enabled()) {
            // existing row may stay; let the caller refresh the menu without
            // crashing on a stale button
            boolean stillSubscribed = subscriptions.exists(workflowId, recipientId);
            return new ToggleOutcome(stillSubscribed, false, "no longer available");
        }
        if (subscriptions.exists(workflowId, recipientId)) {
            subscriptions.delete(workflowId, recipientId);
            log.info("subscription_removed workflowId={} recipientId={}", workflowId, recipientId);
            return new ToggleOutcome(false, true, null);
        }
        subscriptions.insert(workflowId, recipientId);
        log.info("subscription_added workflowId={} recipientId={}", workflowId, recipientId);
        return new ToggleOutcome(true, true, null);
    }

    /** Idempotent subscribe for the REST surface; same approval guard. */
    public boolean subscribe(UUID workflowId, UUID recipientId) {
        ToggleOutcome out = toggle(workflowId, recipientId);
        if (!out.subscribed && out.changed) {
            // toggle removed an existing row; put it back to honour "subscribe"
            subscriptions.insert(workflowId, recipientId);
            return true;
        }
        return out.subscribed;
    }

    public boolean unsubscribe(UUID workflowId, UUID recipientId) {
        return subscriptions.delete(workflowId, recipientId);
    }

    /** Used by the bot menu to render the keyboard with current checkmarks. */
    public List<Recipient> listApprovedSubscribers(UUID workflowId) {
        return subscriptions.listApprovedSubscribers(workflowId);
    }

    public long countSubscribers(UUID workflowId) {
        return subscriptions.countSubscribers(workflowId);
    }

    public boolean isSubscribed(UUID workflowId, UUID recipientId) {
        return subscriptions.exists(workflowId, recipientId);
    }

    public record ToggleOutcome(boolean subscribed, boolean changed, String rejection) {
    }

    /**
     * Builds the dynamic /subscriptions menu for one recipient: header text +
     * one inline-keyboard row per subscribable+enabled workflow, checkmarked
     * with the recipient's current state. callback_data uses {@code sub:<uuid>}
     * — 40 chars, comfortably under Telegram's 64-byte cap. Empty publish set
     * → friendly header, empty keyboard.
     */
    public Menu buildMenu(UUID recipientId) {
        List<Workflow> available = workflows.findSubscribableEnabled();
        if (available.isEmpty()) {
            return new Menu(
                    "📋 Subscriptions\n\nThe owner hasn't published any subscriptions yet.",
                    List.of());
        }
        StringBuilder header = new StringBuilder(
                "📋 Subscriptions\n\nTap a workflow to subscribe or unsubscribe. "
                        + "Notifications for that workflow start (or stop) right away.");
        List<List<Map<String, Object>>> keyboard = new ArrayList<>();
        for (Workflow workflow : available) {
            boolean subscribed = subscriptions.exists(workflow.id(), recipientId);
            String label = (subscribed ? "✅ " : "⬜ ") + workflow.name();
            Map<String, Object> button = new LinkedHashMap<>();
            button.put("text", label);
            button.put("callback_data", "sub:" + workflow.id());
            keyboard.add(List.of(button));
        }
        return new Menu(header.toString(), keyboard);
    }

    public record Menu(String text, List<List<Map<String, Object>>> keyboard) {
    }
}
