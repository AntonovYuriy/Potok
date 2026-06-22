package io.potok.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static String key() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) i;
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    @DisplayName("encrypt/decrypt round-trips and ciphertext differs from plaintext")
    void roundTrip() {
        SecretCipher cipher = new SecretCipher(key());
        String secret = "app-password-1234";

        String encrypted = cipher.encrypt(secret);

        assertThat(encrypted).isNotEqualTo(secret).doesNotContain(secret);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    @DisplayName("same plaintext encrypts to different ciphertext (random IV)")
    void randomIv() {
        SecretCipher cipher = new SecretCipher(key());
        assertThat(cipher.encrypt("x")).isNotEqualTo(cipher.encrypt("x"));
    }

    @Test
    @DisplayName("disabled without a key; encrypt then refuses")
    void disabledWithoutKey() {
        SecretCipher cipher = new SecretCipher("");

        assertThat(cipher.isEnabled()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POTOK_SECRET_KEY");
    }

    @Test
    @DisplayName("rejects a key that is not 32 bytes")
    void rejectsWrongLengthKey() {
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("decrypting with a different key fails (GCM auth)")
    void wrongKeyFails() {
        SecretCipher a = new SecretCipher(key());
        String encrypted = a.encrypt("secret");

        byte[] other = new byte[32];
        java.util.Arrays.fill(other, (byte) 9);
        SecretCipher b = new SecretCipher(Base64.getEncoder().encodeToString(other));

        assertThatThrownBy(() -> b.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }
}
