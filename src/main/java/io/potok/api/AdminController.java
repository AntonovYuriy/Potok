package io.potok.api;

import io.potok.execution.RetentionPurger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Root-only operations. The regular auth filter already ran; these endpoints
 * additionally require the POTOK_API_KEY bootstrap key — ordinary api_token
 * credentials are not enough. With auth disabled (local dev) they are open.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ApiKeyAuthFilter auth;
    private final RetentionPurger purger;

    public AdminController(ApiKeyAuthFilter auth, RetentionPurger purger) {
        this.auth = auth;
        this.purger = purger;
    }

    @PostMapping("/purge")
    public Map<String, Object> purge(
            @RequestHeader(value = ApiKeyAuthFilter.HEADER, required = false) String key) {
        if (auth.isEnabled() && !auth.isRootKey(key)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "admin endpoints require the root POTOK_API_KEY, not an api token");
        }
        int purged = purger.purge();
        return Map.of("purgedExecutions", purged);
    }
}
