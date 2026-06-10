package io.potok;

import io.potok.trigger.WebhookSignatureVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signed webhooks and token lifecycle. The HMAC secret env var is wired via
 * a JVM-level env in CI? No — the verifier reads real env; here the workflow
 * references TEST_HOOK_SECRET which IS set for the gradle test JVM (build.gradle.kts).
 */
@TestPropertySource(properties = "potok.api-key=root-key-42")
class ChannelSecurityIntegrationTest extends IntegrationTestBase {

    private static final String ROOT = "root-key-42";

    private HttpHeaders auth(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", key);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @SuppressWarnings("unchecked")
    void tokenLifecycle() {
        // create with root key
        ResponseEntity<Map<String, Object>> created = rest.exchange("/api/tokens", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "ci-bot"), auth(ROOT)), MAP_TYPE);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = (String) created.getBody().get("token");
        assertThat(token).startsWith("ptk_").hasSizeGreaterThan(20);
        String tokenId = String.valueOf(created.getBody().get("id"));

        // token works for regular API
        HttpHeaders tokenAuth = auth(token);
        assertThat(rest.exchange("/api/workflows", HttpMethod.GET,
                new HttpEntity<>(tokenAuth), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // list shows metadata, never hashes or plaintext
        ResponseEntity<String> list = rest.exchange("/api/tokens", HttpMethod.GET,
                new HttpEntity<>(auth(token)), String.class);
        assertThat(list.getBody()).contains("ci-bot").doesNotContain(token).doesNotContain("token_hash");

        // revoke -> token stops working, root still works
        assertThat(rest.exchange("/api/tokens/" + tokenId, HttpMethod.DELETE,
                new HttpEntity<>(auth(ROOT)), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange("/api/workflows", HttpMethod.GET,
                new HttpEntity<>(tokenAuth), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/api/workflows", HttpMethod.GET,
                new HttpEntity<>(auth(ROOT)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void signedWebhookEnforced() {
        HttpHeaders create = auth(ROOT);
        create.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<Map<String, Object>> wf = rest.exchange("/api/workflows", HttpMethod.POST,
                new HttpEntity<>("""
                        name: signed-hook
                        trigger:
                          webhook: { path: "signed", hmac_secret_env: "TEST_HOOK_SECRET" }
                        steps:
                          - { name: a, action: http, with: { url: "https://example.com", fail_on_status: false } }
                        """, create), MAP_TYPE);
        assertThat(wf.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        byte[] body = "{\"event\":\"push\"}".getBytes(StandardCharsets.UTF_8);
        String goodSignature = "sha256=" + WebhookSignatureVerifier.hmacSha256Hex(
                System.getenv("TEST_HOOK_SECRET"), body);

        // missing signature -> 401
        HttpHeaders plain = new HttpHeaders();
        plain.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange("/hooks/signed", HttpMethod.POST,
                new HttpEntity<>(body, plain), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // wrong signature -> 401
        HttpHeaders bad = new HttpHeaders();
        bad.setContentType(MediaType.APPLICATION_JSON);
        bad.set(WebhookSignatureVerifier.HEADER, "sha256=" + "0".repeat(64));
        assertThat(rest.exchange("/hooks/signed", HttpMethod.POST,
                new HttpEntity<>(body, bad), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // valid signature -> 202
        HttpHeaders good = new HttpHeaders();
        good.setContentType(MediaType.APPLICATION_JSON);
        good.set(WebhookSignatureVerifier.HEADER, goodSignature);
        assertThat(rest.exchange("/hooks/signed", HttpMethod.POST,
                new HttpEntity<>(body, good), String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void unsignedWebhookStillWorksWithoutSecretConfig() {
        HttpHeaders create = auth(ROOT);
        create.setContentType(MediaType.TEXT_PLAIN);
        rest.exchange("/api/workflows", HttpMethod.POST, new HttpEntity<>("""
                name: unsigned-hook
                trigger:
                  webhook: { path: "unsigned" }
                steps:
                  - { name: a, action: http, with: { url: "https://example.com", fail_on_status: false } }
                """, create), MAP_TYPE);

        assertThat(postJson("/hooks/unsigned", Map.of("x", 1)).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }
}
