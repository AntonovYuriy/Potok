package io.potok.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public bootstrap info for the dashboard: is auth on, what version runs. */
@RestController
public class MetaController {

    private final ApiKeyAuthFilter authFilter;
    private final String version;

    public MetaController(ApiKeyAuthFilter authFilter,
                          @Value("${spring.application.name:potok}") String appName) {
        this.authFilter = authFilter;
        this.version = appName;
    }

    @GetMapping("/api/meta")
    public Map<String, Object> meta() {
        return Map.of(
                "app", version,
                "authRequired", authFilter.isEnabled());
    }
}
