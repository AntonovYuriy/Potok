package io.potok.api;

import io.potok.recipient.Recipient;
import io.potok.recipient.RecipientRepository;
import io.potok.recipient.RecipientService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Telegram recipient directory. These endpoints govern WHO RECEIVES bot
 * messages — they grant no access to the Potok control plane (which stays
 * behind {@code X-API-Key}/{@code api_token} always). All endpoints require
 * the same token auth as the rest of /api/**.
 */
@RestController
@RequestMapping("/api/recipients")
public class RecipientController {

    private static final int MAX_PAGE_SIZE = 200;

    private final RecipientRepository recipients;
    private final RecipientService service;

    public RecipientController(RecipientRepository recipients, RecipientService service) {
        this.recipients = recipients;
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status) {
        int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int boundedPage = Math.max(page, 0);
        Recipient.Status filter = parseStatus(status);
        List<Recipient> items = recipients.list(filter, boundedSize, boundedPage * boundedSize);
        return Map.of(
                "items", items.stream().map(RecipientController::toResponse).toList(),
                "page", boundedPage,
                "size", boundedSize,
                "pending", recipients.countByStatus(Recipient.Status.PENDING),
                "approved", recipients.countByStatus(Recipient.Status.APPROVED),
                "revoked", recipients.countByStatus(Recipient.Status.REVOKED));
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable UUID id) {
        try {
            return toResponse(service.approve(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable UUID id) {
        try {
            return toResponse(service.revoke(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!recipients.deleteById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recipient " + id + " not found");
        }
        return ResponseEntity.noContent().build();
    }

    private static Recipient.Status parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Recipient.Status.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be PENDING, APPROVED, or REVOKED");
        }
    }

    /** Masks the chat id slightly so a screenshot of the dashboard does not leak ids verbatim. */
    static Map<String, Object> toResponse(Recipient r) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", r.id());
        body.put("displayName", r.displayName());
        body.put("chatIdMasked", maskChatId(r.chatId()));
        body.put("status", r.status().name());
        body.put("source", r.source());
        body.put("createdAt", r.createdAt());
        body.put("approvedAt", r.approvedAt());
        body.put("lastSeenAt", r.lastSeenAt());
        return body;
    }

    static String maskChatId(String chatId) {
        if (chatId == null || chatId.length() <= 4) {
            return "•••";
        }
        return "•••" + chatId.substring(chatId.length() - 4);
    }
}
