package io.potok.api;

import io.potok.action.EmailProperties;
import io.potok.action.SmtpConfig;
import io.potok.api.SmtpSettingsRepository.SmtpRow;
import io.potok.api.SmtpSettingsService.SmtpUpdate;
import io.potok.api.SmtpSettingsService.SmtpView;
import io.potok.common.SecretCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpSettingsServiceTest {

    private static EmailProperties env(String host) {
        return new EmailProperties(host, 587, "envuser", "envpass", "env@x.com", true, true);
    }

    private static SmtpRow dbRow(String enc) {
        return new SmtpRow("smtp.db", 2525, "dbuser", "db@x.com", true, true, enc);
    }

    @Test
    @DisplayName("DB config wins over env when complete")
    void dbWinsOverEnv() {
        SmtpSettingsRepository repo = mock(SmtpSettingsRepository.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(repo.find()).thenReturn(Optional.of(dbRow("CIPHER")));
        when(cipher.isEnabled()).thenReturn(true);
        when(cipher.decrypt("CIPHER")).thenReturn("realpw");

        SmtpConfig config = new SmtpSettingsService(repo, cipher, env("smtp.env")).resolve();

        assertThat(config.source()).isEqualTo(SmtpConfig.Source.DB);
        assertThat(config.host()).isEqualTo("smtp.db");
        assertThat(config.password()).isEqualTo("realpw");
    }

    @Test
    @DisplayName("falls back to env when no DB row")
    void envWhenNoDbRow() {
        SmtpSettingsRepository repo = mock(SmtpSettingsRepository.class);
        when(repo.find()).thenReturn(Optional.empty());

        SmtpConfig config = new SmtpSettingsService(repo, mock(SecretCipher.class), env("smtp.env")).resolve();

        assertThat(config.source()).isEqualTo(SmtpConfig.Source.ENV);
        assertThat(config.host()).isEqualTo("smtp.env");
    }

    @Test
    @DisplayName("none when neither DB nor env configured")
    void noneWhenNothing() {
        SmtpSettingsRepository repo = mock(SmtpSettingsRepository.class);
        when(repo.find()).thenReturn(Optional.empty());

        SmtpConfig config = new SmtpSettingsService(repo, mock(SecretCipher.class), env(null)).resolve();

        assertThat(config.configured()).isFalse();
        assertThat(config.source()).isEqualTo(SmtpConfig.Source.NONE);
    }

    @Test
    @DisplayName("DB row without a stored password falls back to env (incomplete)")
    void incompleteDbFallsBack() {
        SmtpSettingsRepository repo = mock(SmtpSettingsRepository.class);
        when(repo.find()).thenReturn(Optional.of(dbRow(null)));

        SmtpConfig config = new SmtpSettingsService(repo, mock(SecretCipher.class), env("smtp.env")).resolve();

        assertThat(config.source()).isEqualTo(SmtpConfig.Source.ENV);
    }

    @Test
    @DisplayName("save without a password preserves the stored secret")
    void saveKeepsExistingPassword() {
        SmtpSettingsRepository repo = mock(SmtpSettingsRepository.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(repo.find()).thenReturn(Optional.of(dbRow("OLD_CIPHER")));
        SmtpSettingsService service = new SmtpSettingsService(repo, cipher, env(null));

        service.save(new SmtpUpdate("smtp.new", 587, "u", "f@x.com", true, true, null));

        verify(cipher, never()).encrypt(any());
        ArgumentCaptor<String> pw = ArgumentCaptor.forClass(String.class);
        verify(repo).save(eq("smtp.new"), eq(587), eq("u"), eq("f@x.com"), eq(true), eq(true), pw.capture());
        assertThat(pw.getValue()).isEqualTo("OLD_CIPHER");
    }

    @Test
    @DisplayName("save with a new password encrypts it")
    void saveEncryptsNewPassword() {
        SmtpSettingsRepository repo = mock(SmtpSettingsRepository.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.isEnabled()).thenReturn(true);
        when(cipher.encrypt("newpw")).thenReturn("NEW_CIPHER");
        SmtpSettingsService service = new SmtpSettingsService(repo, cipher, env(null));

        service.save(new SmtpUpdate("smtp.new", 587, "u", "f@x.com", true, true, "newpw"));

        verify(repo).save(any(), any(), any(), any(), eq(true), eq(true), eq("NEW_CIPHER"));
    }

    @Test
    @DisplayName("save with a password but no key is refused")
    void saveRefusedWithoutKey() {
        SmtpSettingsRepository repo = mock(SmtpSettingsRepository.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.isEnabled()).thenReturn(false);
        SmtpSettingsService service = new SmtpSettingsService(repo, cipher, env(null));

        assertThatThrownBy(() ->
                service.save(new SmtpUpdate("h", 587, "u", "f@x.com", true, true, "secret")))
                .isInstanceOf(SmtpSettingsService.SecretKeyMissingException.class)
                .hasMessageContaining("POTOK_SECRET_KEY");
        verify(repo, never()).save(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    @DisplayName("view reports source and password_set, never the secret")
    void viewHidesSecret() {
        SmtpSettingsRepository repo = mock(SmtpSettingsRepository.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(repo.find()).thenReturn(Optional.of(dbRow("CIPHER")));
        when(cipher.isEnabled()).thenReturn(true);
        when(cipher.decrypt("CIPHER")).thenReturn("realpw");

        SmtpView view = new SmtpSettingsService(repo, cipher, env(null)).view();

        assertThat(view.source()).isEqualTo("db");
        assertThat(view.passwordSet()).isTrue();
        assertThat(view.host()).isEqualTo("smtp.db");
        // SmtpView is a record with no password component — the secret cannot leak through it
        assertThat(view.toString()).doesNotContain("realpw").doesNotContain("CIPHER");
    }
}
