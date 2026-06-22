package io.potok.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for secrets stored at rest (e.g. the SMTP password).
 * The key is supplied as base64-encoded 32 bytes via {@code POTOK_SECRET_KEY}
 * (generate with {@code openssl rand -base64 32}). When unset the cipher is
 * disabled — callers must refuse to store new secrets, but env-based config
 * keeps working.
 *
 * <p>Wire format (then base64): {@code [12-byte IV][ciphertext + 16-byte tag]}.
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${potok.secret-key:}") String secretKey) {
        this.key = parseKey(secretKey);
    }

    private static SecretKeySpec parseKey(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(secretKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("POTOK_SECRET_KEY must be base64-encoded", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "POTOK_SECRET_KEY must decode to 32 bytes (got " + raw.length + ")");
        }
        return new SecretKeySpec(raw, "AES");
    }

    /** True when a valid key is configured and secrets can be stored. */
    public boolean isEnabled() {
        return key != null;
    }

    /** @return base64 of IV+ciphertext+tag; never logs the plaintext. */
    public String encrypt(String plaintext) {
        requireEnabled();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        requireEnabled();
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            ByteBuffer buffer = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] ct = new byte[buffer.remaining()];
            buffer.get(ct);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }

    private void requireEnabled() {
        if (key == null) {
            throw new IllegalStateException("POTOK_SECRET_KEY is not configured");
        }
    }
}
