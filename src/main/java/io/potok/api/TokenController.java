package io.potok.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API token management. The plaintext token is returned exactly ONCE on
 * creation; only its SHA-256 lands in the database.
 */
@RestController
@RequestMapping("/api/tokens")
public class TokenController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiTokenRepository tokens;

    public TokenController(ApiTokenRepository tokens) {
        this.tokens = tokens;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'name' is required");
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = "ptk_" + HexFormat.of().formatHex(raw);
        ApiTokenRepository.TokenMeta meta = tokens.insert(name.trim(), ApiKeyAuthFilter.sha256Hex(token));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", meta.id(),
                "name", meta.name(),
                "token", token,
                "note", "store this token now — it is shown only once"));
    }

    @GetMapping
    public List<ApiTokenRepository.TokenMeta> list() {
        return tokens.list();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        if (!tokens.revoke(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "token " + id + " not found or already revoked");
        }
        return ResponseEntity.noContent().build();
    }
}
