package io.potok;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Help → Connect & API doc invariants:
 *  - the asset is served from the jar so the dashboard can render it
 *  - docs/integration.md is a byte-for-byte mirror (no silent drift)
 *  - the open paths documented in the asset are actually open, and the documented
 *    /api/** paths require X-API-Key — so the doc can't drift from auth reality.
 */
@TestPropertySource(properties = "potok.api-key=integration-docs-key")
class HelpIntegrationDocsTest extends IntegrationTestBase {

    @Test
    void connectMdServedFromJar() {
        ResponseEntity<String> response = rest.getForEntity("/help/connect.md", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("# Connect & API")
                .contains("X-Hub-Signature-256")
                .contains("X-API-Key")
                .contains("POST /hooks/{path}".substring(0, 11)); // tolerant of minor wording shifts
    }

    @Test
    void docsMirrorMatchesAssetByteForByte() throws IOException {
        // CWD is the project root in Gradle's test task (see TemplatesIntegrationTest).
        String asset = Files.readString(Path.of("src/main/resources/static/help/connect.md"));
        String mirror = Files.readString(Path.of("docs/integration.md"));
        assertThat(mirror)
                .as("docs/integration.md must mirror src/main/resources/static/help/connect.md "
                        + "verbatim — `cp src/main/resources/static/help/connect.md docs/integration.md` to resync")
                .isEqualTo(asset);
    }

    @Test
    void documentedOpenPathsNeedNoKey() {
        // /api/meta — public discovery, returns authRequired
        ResponseEntity<Map<String, Object>> meta = rest.exchange(
                "/api/meta", HttpMethod.GET, HttpEntity.EMPTY, MAP_TYPE);
        assertThat(meta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meta.getBody().get("authRequired")).isEqualTo(true);

        // /actuator/health — open
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // /hooks/** — auth filter never runs; unknown path is 404 (not 401)
        assertThat(postJson("/hooks/no-such-workflow", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The dashboard asset itself
        assertThat(rest.getForEntity("/help/connect.md", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void documentedProtectedPathsReturn401WithoutKey() {
        for (String path : new String[] {
                "/api/workflows",
                "/api/executions",
                "/api/dlq",
                "/api/tokens",
        }) {
            ResponseEntity<Map<String, Object>> response = rest.exchange(
                    path, HttpMethod.GET, HttpEntity.EMPTY, MAP_TYPE);
            assertThat(response.getStatusCode())
                    .as("%s must require X-API-Key when POTOK_API_KEY is set", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getContentType().toString())
                    .contains("problem+json");
        }
        // /api/admin/purge is even stricter (root-only), but the unauthenticated
        // case still returns 401 via the same filter.
        HttpHeaders headers = new HttpHeaders();
        ResponseEntity<Map<String, Object>> purge = rest.exchange(
                "/api/admin/purge", HttpMethod.POST, new HttpEntity<>(headers), MAP_TYPE);
        assertThat(purge.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
