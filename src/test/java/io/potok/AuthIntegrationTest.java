package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** With POTOK_API_KEY set: /api/** + metrics/info need the key; /api/meta, /hooks, health stay open. */
@TestPropertySource(properties = "potok.api-key=secret-key-123")
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class AuthIntegrationTest extends IntegrationTestBase {

    private ResponseEntity<Map<String, Object>> get(String url, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);
    }

    @Test
    void apiRequiresKey() {
        ResponseEntity<Map<String, Object>> noKey = get("/api/workflows", null);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noKey.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat((String) noKey.getBody().get("detail")).contains("X-API-Key");

        assertThat(get("/api/workflows", "wrong").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders good = new HttpHeaders();
        good.set("X-API-Key", "secret-key-123");
        assertThat(rest.exchange("/api/workflows", HttpMethod.GET,
                        new HttpEntity<>(good), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void metaHooksAndHealthStayOpen() {
        ResponseEntity<Map<String, Object>> meta = get("/api/meta", null);
        assertThat(meta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meta.getBody().get("authRequired")).isEqualTo(true);

        // unknown hook path → 404 (handler reached), not 401
        assertThat(postJson("/hooks/nothing-here", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        // dashboard assets stay open
        assertThat(rest.getForEntity("/", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/js/app.js", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void lastUsedStampIsThrottledNotPerRequest() {
        HttpHeaders root = new HttpHeaders();
        root.set("X-API-Key", "secret-key-123");
        ResponseEntity<Map<String, Object>> minted = rest.exchange("/api/tokens", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "throttle-probe"), withJson(root)), MAP_TYPE);
        assertThat(minted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = (String) minted.getBody().get("token");
        String tokenId = (String) minted.getBody().get("id");

        // first authenticated call stamps last_used_at
        assertThat(listWorkflows(token)).isEqualTo(HttpStatus.OK);
        String first = lastUsedAt(tokenId, root);
        assertThat(first).isNotNull();

        // immediate second call is authenticated but does NOT write the stamp again
        assertThat(listWorkflows(token)).isEqualTo(HttpStatus.OK);
        assertThat(lastUsedAt(tokenId, root)).as("stamp throttled within the window").isEqualTo(first);

        // once the stored stamp is older than the throttle window, the next call refreshes it
        jdbc.sql("update api_token set last_used_at = now() - interval '2 minutes' where id = :id::uuid")
                .param("id", tokenId).update();
        String backdated = lastUsedAt(tokenId, root);
        assertThat(listWorkflows(token)).isEqualTo(HttpStatus.OK);
        assertThat(lastUsedAt(tokenId, root)).as("stale stamp refreshed").isNotEqualTo(backdated);
    }

    private HttpStatus listWorkflows(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        return (HttpStatus) rest.exchange("/api/workflows", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode();
    }

    private static HttpHeaders withJson(HttpHeaders base) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(base);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private String lastUsedAt(String tokenId, HttpHeaders auth) {
        ResponseEntity<java.util.List> list = rest.exchange("/api/tokens", HttpMethod.GET,
                new HttpEntity<>(auth), java.util.List.class);
        for (Map<String, Object> meta : (java.util.List<Map<String, Object>>) list.getBody()) {
            if (tokenId.equals(meta.get("id"))) {
                return (String) meta.get("lastUsedAt");
            }
        }
        throw new AssertionError("token " + tokenId + " not in /api/tokens list");
    }

    @Test
    void metricsAndInfoNeedKeyButHealthStaysOpen() {
        // health probes stay open for uptime pingers…
        assertThat(get("/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        // …but metrics and build-info are guarded (operational intelligence)
        assertThat(get("/actuator/prometheus", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/actuator/info", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders good = new HttpHeaders();
        good.set("X-API-Key", "secret-key-123");
        assertThat(rest.exchange("/actuator/prometheus", HttpMethod.GET,
                        new HttpEntity<>(good), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
