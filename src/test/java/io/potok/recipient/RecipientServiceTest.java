package io.potok.recipient;

import io.potok.api.SettingsRepository;
import io.potok.recipient.Recipient.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recipient state machine + bot reply logic. The repo/settings are mocked so
 * these tests pin the rules without a database.
 */
@ExtendWith(MockitoExtension.class)
class RecipientServiceTest {

    @Mock RecipientRepository repository;
    @Mock SettingsRepository settings;

    RecipientService service;

    @BeforeEach
    void setUp() {
        service = new RecipientService(repository, settings);
    }

    private Recipient existing(Status status) {
        return new Recipient(UUID.randomUUID(), "100", "Alice", status, "telegram",
                Instant.now(), status == Status.APPROVED ? Instant.now() : null, Instant.now());
    }

    @Test
    @DisplayName("auto-approve OFF: new chat lands PENDING")
    void newChatPendingWhenAutoApproveOff() {
        when(repository.findByChatId("100")).thenReturn(Optional.empty());
        when(settings.getBoolean(eq("telegram_auto_approve"), anyBoolean())).thenReturn(false);
        when(repository.upsertOnContact(eq("100"), eq("Alice"), eq(Status.PENDING)))
                .thenReturn(existing(Status.PENDING));

        RecipientService.Contact result = service.onIncomingMessage("100", "Alice");

        assertThat(result.justRegistered()).isTrue();
        assertThat(result.recipient().status()).isEqualTo(Status.PENDING);
    }

    @Test
    @DisplayName("auto-approve ON: new chat lands APPROVED immediately")
    void newChatApprovedWhenAutoApproveOn() {
        when(repository.findByChatId("100")).thenReturn(Optional.empty());
        when(settings.getBoolean(eq("telegram_auto_approve"), anyBoolean())).thenReturn(true);
        when(repository.upsertOnContact(eq("100"), eq("Alice"), eq(Status.APPROVED)))
                .thenReturn(existing(Status.APPROVED));

        RecipientService.Contact result = service.onIncomingMessage("100", "Alice");

        assertThat(result.justRegistered()).isTrue();
        assertThat(result.recipient().status()).isEqualTo(Status.APPROVED);
    }

    @Test
    @DisplayName("repeated message keeps existing status (auto-approve off does not demote APPROVED)")
    void repeatedMessageKeepsExistingStatus() {
        Recipient row = existing(Status.APPROVED);
        when(repository.findByChatId("100")).thenReturn(Optional.of(row));
        when(repository.upsertOnContact(eq("100"), eq("Alice"), eq(Status.APPROVED)))
                .thenReturn(row);

        RecipientService.Contact result = service.onIncomingMessage("100", "Alice");

        assertThat(result.justRegistered()).isFalse();
        verify(settings, never()).getBoolean(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("/stop self-revokes (idempotent on unknown chat)")
    void stopSelfRevokes() {
        Recipient row = existing(Status.APPROVED);
        when(repository.findByChatId("100")).thenReturn(Optional.of(row));

        Optional<String> reply = service.handleBotMessage("100", "Alice", "/stop");

        assertThat(reply).isPresent();
        assertThat(reply.get()).contains("unsubscribed");
        verify(repository).updateStatus(row.id(), Status.REVOKED);
    }

    @Test
    @DisplayName("PENDING → APPROVED via approve()")
    void approvePending() {
        Recipient pending = existing(Status.PENDING);
        when(repository.findById(pending.id())).thenReturn(Optional.of(pending), Optional.of(existing(Status.APPROVED)));

        Recipient result = service.approve(pending.id());

        verify(repository).updateStatus(pending.id(), Status.APPROVED);
        assertThat(result.status()).isEqualTo(Status.APPROVED);
    }

    @Test
    @DisplayName("REVOKED → APPROVED via approve() (re-approve)")
    void reApprovePreviouslyRevoked() {
        Recipient revoked = existing(Status.REVOKED);
        when(repository.findById(revoked.id())).thenReturn(Optional.of(revoked), Optional.of(existing(Status.APPROVED)));

        Recipient result = service.approve(revoked.id());

        verify(repository).updateStatus(revoked.id(), Status.APPROVED);
        assertThat(result.status()).isEqualTo(Status.APPROVED);
    }

    @Test
    @DisplayName("approve() on already APPROVED is a no-op")
    void approveAlreadyApprovedIsNoop() {
        Recipient approved = existing(Status.APPROVED);
        when(repository.findById(approved.id())).thenReturn(Optional.of(approved));

        Recipient result = service.approve(approved.id());

        verify(repository, never()).updateStatus(any(), any());
        assertThat(result.status()).isEqualTo(Status.APPROVED);
    }

    @Test
    @DisplayName("revoke() PENDING → REVOKED")
    void revokePending() {
        Recipient pending = existing(Status.PENDING);
        when(repository.findById(pending.id())).thenReturn(Optional.of(pending), Optional.of(existing(Status.REVOKED)));

        Recipient result = service.revoke(pending.id());

        verify(repository).updateStatus(pending.id(), Status.REVOKED);
        assertThat(result.status()).isEqualTo(Status.REVOKED);
    }

    @Test
    @DisplayName("transitions throw IllegalArgumentException on unknown id")
    void unknownIdThrows() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    @DisplayName("PENDING /start replies 'waiting for the owner'")
    void pendingStartReply() {
        when(repository.findByChatId("100")).thenReturn(Optional.empty());
        when(settings.getBoolean(eq("telegram_auto_approve"), anyBoolean())).thenReturn(false);
        when(repository.upsertOnContact(eq("100"), eq("Alice"), eq(Status.PENDING)))
                .thenReturn(existing(Status.PENDING));

        Optional<String> reply = service.handleBotMessage("100", "Alice", "/start");

        assertThat(reply).hasValueSatisfying(text -> assertThat(text).contains("waiting for the owner"));
    }

    @Test
    @DisplayName("APPROVED returning chat is silent on plain text")
    void approvedReturningChatSilent() {
        Recipient row = existing(Status.APPROVED);
        when(repository.findByChatId("100")).thenReturn(Optional.of(row));
        when(repository.upsertOnContact(eq("100"), eq("Alice"), eq(Status.APPROVED))).thenReturn(row);

        Optional<String> reply = service.handleBotMessage("100", "Alice", "hello bot");

        assertThat(reply).isEmpty();
    }

    @Test
    @DisplayName("/status reports current state")
    void statusCommandReportsState() {
        Recipient row = existing(Status.APPROVED);
        when(repository.findByChatId("100")).thenReturn(Optional.of(row));
        when(repository.upsertOnContact(eq("100"), eq("Alice"), eq(Status.APPROVED))).thenReturn(row);

        Optional<String> reply = service.handleBotMessage("100", "Alice", "/status");

        assertThat(reply).hasValueSatisfying(text -> assertThat(text).contains("Subscribed"));
    }

}
