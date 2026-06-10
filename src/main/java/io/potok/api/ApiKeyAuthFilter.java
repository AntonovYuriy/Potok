package io.potok.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * API key auth for the control plane (/api/**) via the X-API-Key header.
 * Accepted credentials: the bootstrap "root" key from POTOK_API_KEY, or any
 * active row in api_token (matched by SHA-256, last_used_at stamped).
 * POTOK_API_KEY unset → filter inactive (local dev unchanged).
 * Open by design: /api/meta (UI bootstrap), /hooks/** (webhook trigger),
 * actuator and the dashboard static assets — none of them match /api/**.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final String apiKey;
    private final ApiTokenRepository tokens;

    public ApiKeyAuthFilter(@Value("${potok.api-key:}") String apiKey, ApiTokenRepository tokens) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.tokens = tokens;
    }

    public boolean isEnabled() {
        return !apiKey.isEmpty();
    }

    /** Root key only — for admin endpoints stricter than regular token auth. */
    public boolean isRootKey(String provided) {
        return isEnabled() && provided != null && constantTimeEquals(provided, apiKey);
    }

    static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/meta");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null
                && (constantTimeEquals(provided, apiKey) || tokens.useActiveToken(sha256Hex(provided)))) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"Unauthorized","status":401,\
                "detail":"missing or invalid X-API-Key header"}""");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
