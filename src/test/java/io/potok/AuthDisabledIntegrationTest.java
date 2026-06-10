package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Default (no POTOK_API_KEY): everything open, meta reports auth off. */
class AuthDisabledIntegrationTest extends IntegrationTestBase {

    @Test
    void apiOpenWithoutKeyAndMetaSaysSo() {
        assertThat(rest.getForEntity("/api/workflows", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> meta = rest.getForObject("/api/meta", Map.class);
        assertThat(meta.get("authRequired")).isEqualTo(false);
    }
}
