package io.potok.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Transparent HTTP response decompression. Java's HttpClient does NOT
 * auto-decompress, so a server (or CDN) sending {@code Content-Encoding: gzip}
 * leaves us holding raw compressed bytes. This decodes gzip/deflate before the
 * body reaches templating/extract/conditions.
 *
 * <p>We never send {@code Accept-Encoding}; this only triggers when the server
 * compresses anyway, so uncompressed responses pass through byte-for-byte.
 * Brotli ({@code br}) has no JDK codec and no dependency here — rather than
 * corrupt the body silently we surface {@link UnsupportedEncodingException}.
 */
public final class HttpBodyDecoder {

    private HttpBodyDecoder() {
    }

    /** Thrown for an encoding we deliberately refuse to fake (e.g. brotli). */
    public static class UnsupportedEncodingException extends RuntimeException {
        public UnsupportedEncodingException(String encoding) {
            super("response uses Content-Encoding: " + encoding
                    + " which Potok cannot decompress (no codec/dependency). "
                    + "Potok does not request compression, so the server should not send it.");
        }
    }

    /**
     * Decompress per {@code Content-Encoding}. gzip/deflate decoded; identity
     * and unknown non-handled encodings pass through unchanged; brotli throws.
     */
    public static byte[] decode(String contentEncoding, byte[] body) {
        if (body == null || body.length == 0 || contentEncoding == null || contentEncoding.isBlank()) {
            return body;
        }
        // a chain like "gzip, br" is unusual; act on the outermost (last applied) token
        String[] tokens = contentEncoding.split(",");
        String encoding = tokens[tokens.length - 1].trim().toLowerCase(Locale.ROOT);
        return switch (encoding) {
            case "gzip", "x-gzip" -> gunzip(body);
            case "deflate" -> inflate(body);
            case "br" -> throw new UnsupportedEncodingException("br");
            default -> body; // identity or something we don't recognise — leave as-is
        };
    }

    /** Decompress (if needed) and decode to text using the response charset (default UTF-8). */
    public static String decodeToString(HttpHeaders headers, byte[] body) {
        byte[] decoded = decode(headers.firstValue("content-encoding").orElse(null), body);
        return new String(decoded, charsetOf(headers));
    }

    /** Charset from the Content-Type header's {@code charset=} param, else UTF-8. */
    public static Charset charsetOf(HttpHeaders headers) {
        Optional<String> contentType = headers.firstValue("content-type");
        if (contentType.isPresent()) {
            for (String part : contentType.get().split(";")) {
                String token = part.trim();
                if (token.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try {
                        return Charset.forName(token.substring("charset=".length())
                                .replace("\"", "").trim());
                    } catch (RuntimeException ignored) {
                        // unknown/invalid charset name — fall through to UTF-8
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static byte[] gunzip(byte[] body) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to gunzip response: " + e.getMessage(), e);
        }
    }

    /**
     * Inflate a deflate body. Most servers send zlib-wrapped deflate (RFC 1950);
     * a few send raw/nowrap (RFC 1951). Try zlib first, fall back to nowrap.
     */
    private static byte[] inflate(byte[] body) {
        try {
            return inflate(body, false);
        } catch (IOException zlibFailed) {
            try {
                return inflate(body, true);
            } catch (IOException nowrapFailed) {
                throw new IllegalStateException(
                        "failed to inflate deflate response: " + nowrapFailed.getMessage(), nowrapFailed);
            }
        }
    }

    private static byte[] inflate(byte[] body, boolean nowrap) throws IOException {
        Inflater inflater = new Inflater(nowrap);
        try (InflaterInputStream in =
                     new InflaterInputStream(new ByteArrayInputStream(body), inflater)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length * 2);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
