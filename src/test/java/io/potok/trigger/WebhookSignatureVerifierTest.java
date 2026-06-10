package io.potok.trigger;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureVerifierTest {

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier(Map.of("HOOK_SECRET", "s3cret")::get);

    private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    private String sign(String secret, byte[] body) {
        return "sha256=" + WebhookSignatureVerifier.hmacSha256Hex(secret, body);
    }

    @Test
    void acceptsValidSignature() {
        assertThat(verifier.verify("HOOK_SECRET", sign("s3cret", BODY), BODY))
                .isEqualTo(WebhookSignatureVerifier.Result.OK);
    }

    @Test
    void acceptsUppercaseHeader() {
        String upper = sign("s3cret", BODY).toUpperCase();
        assertThat(verifier.verify("HOOK_SECRET", upper, BODY))
                .isEqualTo(WebhookSignatureVerifier.Result.OK);
    }

    @Test
    void rejectsWrongSecret() {
        assertThat(verifier.verify("HOOK_SECRET", sign("other", BODY), BODY))
                .isEqualTo(WebhookSignatureVerifier.Result.INVALID_SIGNATURE);
    }

    @Test
    void rejectsTamperedBody() {
        assertThat(verifier.verify("HOOK_SECRET", sign("s3cret", BODY),
                "{\"a\":2}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(WebhookSignatureVerifier.Result.INVALID_SIGNATURE);
    }

    @Test
    void rejectsMissingSignature() {
        assertThat(verifier.verify("HOOK_SECRET", null, BODY))
                .isEqualTo(WebhookSignatureVerifier.Result.MISSING_SIGNATURE);
        assertThat(verifier.verify("HOOK_SECRET", "", BODY))
                .isEqualTo(WebhookSignatureVerifier.Result.MISSING_SIGNATURE);
    }

    @Test
    void failsClosedWhenEnvVarUnset() {
        assertThat(verifier.verify("UNSET_VAR", sign("s3cret", BODY), BODY))
                .isEqualTo(WebhookSignatureVerifier.Result.SECRET_NOT_CONFIGURED);
    }

    @Test
    void knownVectorMatchesGitHubFormat() {
        // echo -n 'hello' | openssl dgst -sha256 -hmac 'key'
        assertThat(WebhookSignatureVerifier.hmacSha256Hex("key", "hello".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("9307b3b915efb5171ff14d8cb55fbcc798c6c0ef1456d66ded1a6aa723a58b7b");
    }
}
