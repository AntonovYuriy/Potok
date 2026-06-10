package io.potok.trigger;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * GitHub-style webhook signatures: X-Hub-Signature-256 = "sha256=" + hex(HMAC-SHA256(secret, raw body)).
 * The secret comes from an environment variable named in the workflow YAML; the
 * value never touches the database. Comparison is constant-time.
 */
@Component
public class WebhookSignatureVerifier {

    public static final String HEADER = "X-Hub-Signature-256";

    private final UnaryOperator<String> env;

    public WebhookSignatureVerifier() {
        this(System::getenv);
    }

    WebhookSignatureVerifier(UnaryOperator<String> env) {
        this.env = env;
    }

    public enum Result { OK, MISSING_SIGNATURE, INVALID_SIGNATURE, SECRET_NOT_CONFIGURED }

    public Result verify(String secretEnvName, String signatureHeader, byte[] rawBody) {
        String secret = env.apply(secretEnvName);
        if (secret == null || secret.isBlank()) {
            // fail closed: a workflow that demands signatures must not accept unsigned calls
            return Result.SECRET_NOT_CONFIGURED;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return Result.MISSING_SIGNATURE;
        }
        String expected = "sha256=" + hmacSha256Hex(secret, rawBody);
        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return matches ? Result.OK : Result.INVALID_SIGNATURE;
    }

    public static String hmacSha256Hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
