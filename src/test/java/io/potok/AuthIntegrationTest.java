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

/** With POTOK_API_KEY set: /api/** needs the key, /api/meta, /hooks and actuator stay open. */
@TestPropertySource(properties = "potok.api-key=secret-key-123")
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
}
