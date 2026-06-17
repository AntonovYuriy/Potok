package io.potok.subscription;

import io.potok.definition.Workflow;
import io.potok.definition.WorkflowDefinition;
import io.potok.definition.WorkflowRepository;
import io.potok.recipient.Recipient;
import io.potok.recipient.RecipientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * State rules for {@link SubscriptionService}: only APPROVED recipients can
 * subscribe; only {@code subscribable + enabled} workflows accept toggles;
 * stale buttons against a no-longer-available workflow are a friendly no-op,
 * not a crash; the menu builder paints check-marks per recipient.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionServiceTest {

    SubscriptionRepository repo;
    WorkflowRepository workflows;
    RecipientRepository recipients;
    SubscriptionService service;

    @BeforeEach
    void setUp() {
        repo = mock(SubscriptionRepository.class);
        workflows = mock(WorkflowRepository.class);
        recipients = mock(RecipientRepository.class);
        service = new SubscriptionService(repo, workflows, recipients);
    }

    private Recipient recipient(Recipient.Status status) {
        return new Recipient(UUID.randomUUID(), "100", "Alice", status,
                "telegram", Instant.now(), status == Recipient.Status.APPROVED ? Instant.now() : null,
                Instant.now());
    }

    private Workflow workflow(boolean subscribable, boolean enabled) {
        return new Workflow(UUID.randomUUID(), "wf", enabled, subscribable, "yaml: 1",
                new WorkflowDefinition("wf", null, List.of()), 1,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    @DisplayName("toggle on a non-existing subscription adds it (APPROVED + subscribable)")
    void toggleAdds() {
        Recipient r = recipient(Recipient.Status.APPROVED);
        Workflow w = workflow(true, true);
        when(recipients.findById(r.id())).thenReturn(Optional.of(r));
        when(workflows.findById(w.id())).thenReturn(Optional.of(w));
        when(repo.exists(w.id(), r.id())).thenReturn(false);

        SubscriptionService.ToggleOutcome out = service.toggle(w.id(), r.id());

        assertThat(out.subscribed()).isTrue();
        assertThat(out.changed()).isTrue();
        verify(repo).insert(w.id(), r.id());
    }

    @Test
    @DisplayName("toggle on existing subscription removes it (idempotent rule)")
    void toggleRemoves() {
        Recipient r = recipient(Recipient.Status.APPROVED);
        Workflow w = workflow(true, true);
        when(recipients.findById(r.id())).thenReturn(Optional.of(r));
        when(workflows.findById(w.id())).thenReturn(Optional.of(w));
        when(repo.exists(w.id(), r.id())).thenReturn(true);

        SubscriptionService.ToggleOutcome out = service.toggle(w.id(), r.id());

        assertThat(out.subscribed()).isFalse();
        assertThat(out.changed()).isTrue();
        verify(repo).delete(w.id(), r.id());
    }

    @Test
    @DisplayName("PENDING recipient cannot toggle")
    void pendingRejected() {
        Recipient r = recipient(Recipient.Status.PENDING);
        Workflow w = workflow(true, true);
        when(recipients.findById(r.id())).thenReturn(Optional.of(r));

        SubscriptionService.ToggleOutcome out = service.toggle(w.id(), r.id());

        assertThat(out.changed()).isFalse();
        assertThat(out.rejection()).isEqualTo("not authorised");
        verify(repo, never()).insert(any(), any());
        verify(repo, never()).delete(any(), any());
    }

    @Test
    @DisplayName("REVOKED recipient cannot toggle")
    void revokedRejected() {
        Recipient r = recipient(Recipient.Status.REVOKED);
        Workflow w = workflow(true, true);
        when(recipients.findById(r.id())).thenReturn(Optional.of(r));

        SubscriptionService.ToggleOutcome out = service.toggle(w.id(), r.id());

        assertThat(out.changed()).isFalse();
        assertThat(out.rejection()).isEqualTo("not authorised");
    }

    @Test
    @DisplayName("toggle on non-subscribable workflow is a friendly no-op")
    void notSubscribableNoop() {
        Recipient r = recipient(Recipient.Status.APPROVED);
        Workflow w = workflow(false, true);
        when(recipients.findById(r.id())).thenReturn(Optional.of(r));
        when(workflows.findById(w.id())).thenReturn(Optional.of(w));
        when(repo.exists(w.id(), r.id())).thenReturn(false);

        SubscriptionService.ToggleOutcome out = service.toggle(w.id(), r.id());

        assertThat(out.changed()).isFalse();
        assertThat(out.rejection()).isEqualTo("no longer available");
    }

    @Test
    @DisplayName("toggle on disabled workflow is a friendly no-op")
    void disabledNoop() {
        Recipient r = recipient(Recipient.Status.APPROVED);
        Workflow w = workflow(true, false);
        when(recipients.findById(r.id())).thenReturn(Optional.of(r));
        when(workflows.findById(w.id())).thenReturn(Optional.of(w));
        when(repo.exists(w.id(), r.id())).thenReturn(false);

        SubscriptionService.ToggleOutcome out = service.toggle(w.id(), r.id());

        assertThat(out.changed()).isFalse();
        assertThat(out.rejection()).isEqualTo("no longer available");
    }

    @Test
    @DisplayName("buildMenu paints ✅ for subscribed, ⬜ for not, in workflow name order")
    void menuPaintsCheckmarks() {
        Recipient r = recipient(Recipient.Status.APPROVED);
        Workflow a = workflow(true, true);
        Workflow b = workflow(true, true);
        when(workflows.findSubscribableEnabled()).thenReturn(List.of(a, b));
        when(repo.exists(a.id(), r.id())).thenReturn(true);
        when(repo.exists(b.id(), r.id())).thenReturn(false);

        SubscriptionService.Menu menu = service.buildMenu(r.id());

        assertThat(menu.text()).contains("Subscriptions");
        assertThat(menu.keyboard()).hasSize(2);
        Map<String, Object> firstButton = menu.keyboard().get(0).get(0);
        assertThat((String) firstButton.get("text")).startsWith("✅");
        assertThat((String) firstButton.get("callback_data")).startsWith("sub:" + a.id());
        Map<String, Object> secondButton = menu.keyboard().get(1).get(0);
        assertThat((String) secondButton.get("text")).startsWith("⬜");
    }

    @Test
    @DisplayName("buildMenu with no subscribable workflows returns friendly empty header")
    void menuEmpty() {
        Recipient r = recipient(Recipient.Status.APPROVED);
        when(workflows.findSubscribableEnabled()).thenReturn(List.of());

        SubscriptionService.Menu menu = service.buildMenu(r.id());

        assertThat(menu.text()).contains("hasn't published any subscriptions yet");
        assertThat(menu.keyboard()).isEmpty();
    }

    @Test
    @DisplayName("listApprovedSubscribers delegates to the repository (returns only APPROVED)")
    void listApprovedDelegates() {
        UUID wid = UUID.randomUUID();
        Recipient approved = recipient(Recipient.Status.APPROVED);
        when(repo.listApprovedSubscribers(wid)).thenReturn(List.of(approved));

        assertThat(service.listApprovedSubscribers(wid)).containsExactly(approved);
        verify(repo, times(1)).listApprovedSubscribers(wid);
    }
}
