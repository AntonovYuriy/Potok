package io.potok.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generic HTTP step. with: method, url, headers, body, fail_on_status.
 * Output: {status, headers, body} — body parsed as JSON when possible.
 * Non-2xx responses are step failures (and thus retried) unless
 * fail_on_status: false, which records any response as success so a later
 * step's condition can react to the status (healthcheck pattern).
 */
@Component
public class HttpActionHandler implements ActionHandler {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper;
    private final io.potok.common.UrlGuard urlGuard;

    public HttpActionHandler(ObjectMapper objectMapper, io.potok.common.UrlGuard urlGuard) {
        this.objectMapper = objectMapper;
        this.urlGuard = urlGuard;
    }

    @Override
    public String type() {
        return "http";
    }

    @Override
    public StepResult execute(StepContext ctx) {
        String method = ctx.optionalString("method", "GET").toUpperCase(Locale.ROOT);
        String url = ctx.requireString("url");

        try {
            urlGuard.check(url);
        } catch (io.potok.common.UrlGuard.BlockedUrlException e) {
            return StepResult.fail(e.getMessage());
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT);

        boolean jsonBody = false;
        Object body = ctx.with() == null ? null : ctx.with().get("body");
        HttpRequest.BodyPublisher publisher;
        if (body == null) {
            publisher = HttpRequest.BodyPublishers.noBody();
        } else if (body instanceof String s) {
            publisher = HttpRequest.BodyPublishers.ofString(s);
        } else {
            try {
                publisher = HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
                jsonBody = true;
            } catch (JsonProcessingException e) {
                return StepResult.fail("failed to serialize request body: " + e.getMessage());
            }
        }
        request.method(method, publisher);
        if (jsonBody) {
            request.header("Content-Type", "application/json");
        }

        if (ctx.with() != null && ctx.with().get("headers") instanceof Map<?, ?> headers) {
            headers.forEach((k, v) -> request.header(String.valueOf(k), String.valueOf(v)));
        }

        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", response.statusCode());
            output.put("headers", firstValueHeaders(response));
            output.put("body", parseBody(response.body()));
            boolean failOnStatus = !Boolean.FALSE.equals(
                    ctx.with() == null ? null : ctx.with().get("fail_on_status"));
            if (!failOnStatus || (response.statusCode() >= 200 && response.statusCode() < 300)) {
                return StepResult.ok(output);
            }
            return StepResult.fail("http " + method + " " + url + " returned status " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StepResult.fail("http request interrupted");
        } catch (Exception e) {
            return StepResult.fail("http " + method + " " + url + " failed: " + io.potok.common.Errors.describe(e));
        }
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return objectMapper.readValue(trimmed, Object.class);
            } catch (JsonProcessingException ignored) {
                // not valid JSON after all — fall through to raw string
            }
        }
        return body;
    }

    private static Map<String, String> firstValueHeaders(HttpResponse<String> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!name.startsWith(":") && !values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }
}
