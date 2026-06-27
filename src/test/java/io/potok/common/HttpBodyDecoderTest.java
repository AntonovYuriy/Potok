package io.potok.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpHeaders;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpBodyDecoderTest {

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] raw, boolean nowrap) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, nowrap);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static HttpHeaders headers(Map<String, List<String>> map) {
        return HttpHeaders.of(map, (k, v) -> true);
    }

    @Test
    @DisplayName("gzip body is decompressed")
    void gunzips() throws Exception {
        byte[] body = gzip("hello gzip".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(HttpBodyDecoder.decode("gzip", body), StandardCharsets.UTF_8))
                .isEqualTo("hello gzip");
        assertThat(new String(HttpBodyDecoder.decode("x-gzip", body), StandardCharsets.UTF_8))
                .isEqualTo("hello gzip");
    }

    @Test
    @DisplayName("deflate body (zlib-wrapped) is inflated")
    void inflatesZlib() {
        byte[] body = deflate("hello deflate".getBytes(StandardCharsets.UTF_8), false);
        assertThat(new String(HttpBodyDecoder.decode("deflate", body), StandardCharsets.UTF_8))
                .isEqualTo("hello deflate");
    }

    @Test
    @DisplayName("deflate body (raw/nowrap) is inflated via fallback")
    void inflatesNowrap() {
        byte[] body = deflate("raw deflate".getBytes(StandardCharsets.UTF_8), true);
        assertThat(new String(HttpBodyDecoder.decode("deflate", body), StandardCharsets.UTF_8))
                .isEqualTo("raw deflate");
    }

    @Test
    @DisplayName("no Content-Encoding passes through byte-for-byte")
    void identityUnchanged() {
        byte[] body = "plain".getBytes(StandardCharsets.UTF_8);
        assertThat(HttpBodyDecoder.decode(null, body)).isSameAs(body);
        assertThat(HttpBodyDecoder.decode("identity", body)).isEqualTo(body);
    }

    @Test
    @DisplayName("unknown encoding passes through raw (not corrupted)")
    void unknownPassesThrough() {
        byte[] body = "weird".getBytes(StandardCharsets.UTF_8);
        assertThat(HttpBodyDecoder.decode("compress", body)).isEqualTo(body);
    }

    @Test
    @DisplayName("brotli is surfaced, never silently corrupted")
    void brotliThrows() {
        assertThatThrownBy(() -> HttpBodyDecoder.decode("br", new byte[]{1, 2, 3}))
                .isInstanceOf(HttpBodyDecoder.UnsupportedEncodingException.class)
                .hasMessageContaining("br");
    }

    @Test
    @DisplayName("a chained encoding acts on the outermost token")
    void chainOuterToken() throws Exception {
        byte[] body = gzip("chained".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(HttpBodyDecoder.decode("identity, gzip", body), StandardCharsets.UTF_8))
                .isEqualTo("chained");
    }

    @Test
    @DisplayName("decodeToString honors the Content-Type charset")
    void honorsCharset() {
        byte[] body = "café".getBytes(Charset.forName("ISO-8859-1"));
        HttpHeaders h = headers(Map.of("content-type", List.of("text/plain; charset=ISO-8859-1")));
        assertThat(HttpBodyDecoder.decodeToString(h, body)).isEqualTo("café");
    }

    @Test
    @DisplayName("decodeToString gunzips and then decodes with charset")
    void decodeToStringGzip() throws Exception {
        byte[] body = gzip("zipped".getBytes(StandardCharsets.UTF_8));
        HttpHeaders h = headers(Map.of(
                "content-encoding", List.of("gzip"),
                "content-type", List.of("application/json; charset=utf-8")));
        assertThat(HttpBodyDecoder.decodeToString(h, body)).isEqualTo("zipped");
    }

    @Test
    @DisplayName("missing charset defaults to UTF-8")
    void defaultsUtf8() {
        byte[] body = "naïve".getBytes(StandardCharsets.UTF_8);
        assertThat(HttpBodyDecoder.decodeToString(headers(Map.of()), body)).isEqualTo("naïve");
    }
}
