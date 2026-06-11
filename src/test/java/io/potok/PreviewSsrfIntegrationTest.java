package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSRF guard with its production default (private URLs blocked): a
 * preview that targets the cloud metadata endpoint fails with a clear
 * message instead of fetching it.
 */
class PreviewSsrfIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void guardOn(DynamicPropertyRegistry registry) {
        // overrides the test-classpath default (allow=true for WireMock on localhost)
        registry.add("potok.allow-private-urls", () -> "false");
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewBlocksMetadataEndpoint() {
        ResponseEntity<Map<String, Object>> response = postYaml("/api/preview", """
                name: pv-ssrf
                trigger:
                  cron: "0 7 * * *"
                steps:
                  - name: fetch
                    action: http
                    with:
                      url: http://169.254.169.254/latest/meta-data/
                """);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String, Object>> steps = (List<Map<String, Object>>) response.getBody().get("steps");
        Map<String, Object> fetch = steps.get(0);
        assertThat(fetch.get("mode")).isEqualTo("failed");
        assertThat((String) fetch.get("human_summary"))
                .isEqualTo("URL blocked: it points to a private/internal address");
        assertThat((String) fetch.get("detail")).contains("POTOK_ALLOW_PRIVATE_URLS");
    }
}
