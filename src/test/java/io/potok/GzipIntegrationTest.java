package io.potok;

import io.potok.action.HttpActionHandler;
import io.potok.action.StepContext;
import io.potok.action.StepResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transparent gzip/deflate handling end-to-end: the http action and the poll
 * extract / rss parser all see decompressed bytes. WireMock serves real gzipped
 * bytes with a {@code Content-Encoding: gzip} header.
 */
class GzipIntegrationTest extends IntegrationTestBase {

    @Autowired
    private HttpActionHandler http;

    private static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private void stubGzip(String path, String contentType, String body) throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", contentType)
                .withHeader("Content-Encoding", "gzip")
                .withBody(gzip(body))));
    }

    private StepResult httpGet(String path) {
        return http.execute(new StepContext(null, UUID.randomUUID(), "wf", "fetch",
                Map.of("method", "GET", "url", WIRE_MOCK.baseUrl() + path), 1));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> preview(String yaml) {
        ResponseEntity<Map<String, Object>> response = postYaml("/api/preview", yaml);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("preview: " + response.getBody()).isTrue();
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> trigger(Map<String, Object> result) {
        return (Map<String, Object>) result.get("trigger");
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpGzippedJsonIsDecompressedAndParsed() throws Exception {
        stubGzip("/api.json", "application/json", "{\"price\": 4242, \"name\": \"widget\"}");

        StepResult result = httpGet("/api.json");

        assertThat(result.success()).isTrue();
        Object body = result.output().get("body");
        assertThat(body).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) body).containsEntry("price", 4242).containsEntry("name", "widget");
    }

    @Test
    void uncompressedResponseUnchanged() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/plain.json")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"ok\": true}")));

        StepResult result = httpGet("/plain.json");

        assertThat(result.success()).isTrue();
        assertThat(result.output().get("body")).isEqualTo(Map.of("ok", true));
    }

    @Test
    void brotliIsSurfacedNotCorrupted() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/br")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Encoding", "br")
                .withBody("not really brotli")));

        StepResult result = httpGet("/br");

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("br").contains("cannot decompress");
    }

    @Test
    void pollCssExtractOverGzippedHtml() throws Exception {
        stubGzip("/page", "text/html",
                "<html><body><span class=\"price\">In stock — 199 zł</span></body></html>");

        Map<String, Object> result = preview("""
                name: pv-gz-css
                trigger:
                  poll:
                    interval: 1h
                    http: { method: GET, url: %s/page }
                    extract: { css: "span.price" }
                    fire_when: changed
                steps:
                  - name: notify
                    action: telegram
                    with: { chat_id: "1", text: "{{ trigger.value }}" }
                """.formatted(WIRE_MOCK.baseUrl()));

        assertThat((String) trigger(result).get("human_summary"))
                .contains("element found").contains("In stock — 199 zł");
    }

    @Test
    void pollJsonpathFireWhenOverGzippedJson() throws Exception {
        stubGzip("/rate.json", "application/json", "{\"rates\": [{\"mid\": 4.55}]}");

        Map<String, Object> result = preview("""
                name: pv-gz-json
                trigger:
                  poll:
                    interval: 1h
                    http: { method: GET, url: %s/rate.json }
                    extract: { jsonpath: "$.rates[0].mid" }
                    fire_when: "{{ poll.value > 4.30 }}"
                steps:
                  - name: notify
                    action: telegram
                    with: { chat_id: "1", text: "{{ trigger.value }}" }
                """.formatted(WIRE_MOCK.baseUrl()));

        String summary = (String) trigger(result).get("human_summary");
        assertThat(summary).contains("value found").contains("4.55");
        assertThat(summary).contains("Fire condition is TRUE right now");
    }

    @Test
    void rssOverGzippedFeed() throws Exception {
        String feed = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>Feed</title>
                  <item><title>Episode 42 released</title>
                        <link>https://example.com/42</link>
                        <guid>https://example.com/42</guid></item>
                </channel></rss>
                """;
        stubGzip("/feed.xml", "application/rss+xml", feed);

        Map<String, Object> result = preview("""
                name: pv-gz-rss
                trigger:
                  rss: { interval: 15m, url: %s/feed.xml }
                steps:
                  - name: notify
                    action: telegram
                    with: { chat_id: "1", text: "{{ trigger.title }}" }
                """.formatted(WIRE_MOCK.baseUrl()));

        assertThat((String) trigger(result).get("human_summary"))
                .contains("Feed fetched").contains("Episode 42 released");
    }
}
